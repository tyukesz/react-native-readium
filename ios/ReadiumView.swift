import Combine
import Foundation
import ReadiumShared
import ReadiumStreamer
import UIKit
import ReadiumNavigator


class ReadiumView : UIView, Loggable {
  var readerService: ReaderService = ReaderService()
  var readerViewController: ReaderViewController?
  var viewController: UIViewController? {
    let viewController = sequence(first: self, next: { $0.next }).first(where: { $0 is UIViewController })
    return viewController as? UIViewController
  }
  private var subscriptions = Set<AnyCancellable>()
  private struct SentenceEntry {
    let index: Int
    let start: Int
    let end: Int
    let text: String
    let progression: Double
    let locator: Locator
  }

  private var sentenceIndexCache: [String: [SentenceEntry]] = [:]
  private var sentenceIndexAccess: [String: Date] = [:]
  private let sentenceIndexCacheMax = 4

  private let highlightDecorationGroup = "rn_highlight"
  private let highlightDecorationId = "active"

  private struct HighlightStyleConfig {
    let tint: UIColor
    let isActive: Bool
  }

  @objc var file: NSDictionary? = nil {
    didSet {
      sentenceIndexCache.removeAll()
      sentenceIndexAccess.removeAll()
      let initialLocation = file?["initialLocation"] as? NSDictionary
      if let url = file?["url"] as? String {
        self.loadBook(url: url, location: initialLocation)
      }
    }
  }
  @objc var location: NSDictionary? = nil {
    didSet {
      self.updateLocation()
    }
  }
  @objc var preferences: NSString? = nil {
    didSet {
      sentenceIndexCache.removeAll()
      sentenceIndexAccess.removeAll()
      self.updatePreferences(preferences)
    }
  }
  @objc var onLocationChange: RCTDirectEventBlock?
  @objc var onPublicationReady: RCTDirectEventBlock?
  @objc var onTap: RCTDirectEventBlock?

  func loadBook(
    url: String,
    location: NSDictionary?
  ) {
    guard let rootViewController = UIApplication.shared.delegate?.window??.rootViewController else { return }

    self.readerService.buildViewController(
      url: url,
      bookId: url,
      location: location,
      sender: rootViewController,
      completion: { vc in
        self.addViewControllerAsSubview(vc)
        self.location = location
      }
    )
  }

  func getLocator() async -> Locator? {
    return await ReaderService.locatorFromLocation(location, readerViewController?.publication)
  }

  func updateLocation() {
    Task { @MainActor [weak self] in
      guard let self = self else { return }
      guard let navigator = self.readerViewController?.navigator else {
        return
      }
      guard let locator = await self.getLocator() else {
        return
      }

      let currentLocation = navigator.currentLocation
      if let currentLocation, locator.hashValue == currentLocation.hashValue {
        return
      }

      _ = await navigator.go(
        to: locator,
        options: .animated
      )
    }
  }

  private func normalizeHref(_ href: String) -> String {
    return href.trimmingCharacters(in: .whitespacesAndNewlines)
      .replacingOccurrences(of: "^/+", with: "", options: .regularExpression)
  }

  private func normalizeHref(_ href: AnyURL) -> String {
    // Readium 3.x uses `AnyURL` for Locator.href
    return normalizeHref(href.url.relativeString)
  }

  private func cacheSentenceIndex(href: String, sentences: [SentenceEntry]) {
    let key = normalizeHref(href)
    sentenceIndexCache[key] = sentences
    sentenceIndexAccess[key] = Date()

    if sentenceIndexCache.count > sentenceIndexCacheMax {
      if let oldest = sentenceIndexAccess.min(by: { $0.value < $1.value })?.key {
        sentenceIndexCache.removeValue(forKey: oldest)
        sentenceIndexAccess.removeValue(forKey: oldest)
      }
    }
  }

  private func buildSentenceIndex(href: String) async -> [SentenceEntry] {
    let key = normalizeHref(href)
    guard let publication = readerViewController?.publication else { return [] }

    // Start at the beginning of the requested resource.
    guard let startLocator = await ReaderService.locatorFromLocation(
      [
        "href": href,
        "type": "application/xhtml+xml",
        "locations": [
          "progression": 0
        ]
      ],
      publication
    ) else {
      return []
    }

    guard let iterator = publication.content(from: startLocator)?.iterator() else {
      return []
    }

    let tokenizer = makeTextContentTokenizer(
      defaultLanguage: publication.metadata.language,
      contextSnippetLength: 50,
      textTokenizerFactory: { language in
        makeDefaultTextTokenizer(unit: .sentence, language: language)
      }
    )

    var raw: [(text: String, locator: Locator)] = []
    var started = false

    do {
      while true {
        guard let element = try await iterator.next() else {
          break
        }

        let pieces = try tokenizer(element)
        for piece in pieces {
          guard let textElement = piece as? TextContentElement else {
            continue
          }

          for segment in textElement.segments {
            let segmentHref = normalizeHref(segment.locator.href)
            if segmentHref != key {
              if started {
                // We have moved past the requested resource.
                return finalizeSentenceIndex(raw: raw)
              }
              continue
            }

            started = true
            let t = segment.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty {
              continue
            }

            let split = postSplitSentences(text: t)
            if split.count <= 1 {
              raw.append((text: t, locator: segment.locator))
              continue
            }

            for part in split {
              let quoted = locatorByAddingTextQuote(
                base: segment.locator,
                sourceText: t,
                highlightRange: part.range,
                contextSnippetLength: 50
              )
              raw.append((text: part.text, locator: quoted))
            }
          }
        }
      }
    } catch {
      log(.error, "Failed to build sentence index: \(error)")
    }

    return finalizeSentenceIndex(raw: raw)
  }

  private struct SplitSentencePart {
    let text: String
    let range: Range<String.Index>
  }

  private func postSplitSentences(text: String) -> [SplitSentencePart] {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
      return []
    }

    var boundaries: [String.Index] = [trimmed.startIndex]
    boundaries.reserveCapacity(4)

    let end = trimmed.endIndex
    var i = trimmed.startIndex
    while i < end {
      let ch = trimmed[i]
      if ch == "." || ch == "!" || ch == "?" {
        let afterTerminator = trimmed.index(after: i)
        if ch == ".", afterTerminator < end, trimmed[afterTerminator] == "." {
          // Likely an ellipsis ("...") or dotted abbreviation; don't split mid-run.
          i = afterTerminator
          continue
        }

        if let startOfNext = findSentenceStartIndex(after: afterTerminator, in: trimmed) {
          if shouldSplitAfter(wordBeforeTerminatorAt: i, in: trimmed, terminator: ch) {
            boundaries.append(startOfNext)
          }
        }
      }
      i = trimmed.index(after: i)
    }

    boundaries.append(end)
    boundaries = boundaries.sorted()

    var out: [SplitSentencePart] = []
    out.reserveCapacity(max(1, boundaries.count - 1))

    for idx in 0..<(boundaries.count - 1) {
      var start = boundaries[idx]
      var stop = boundaries[idx + 1]

      while start < stop, trimmed[start].isWhitespace {
        start = trimmed.index(after: start)
      }
      while stop > start, trimmed[trimmed.index(before: stop)].isWhitespace {
        stop = trimmed.index(before: stop)
      }

      if start < stop {
        out.append(SplitSentencePart(text: String(trimmed[start..<stop]), range: start..<stop))
      }
    }

    return out
  }

  private func findSentenceStartIndex(after index: String.Index, in text: String) -> String.Index? {
    var j = index
    let end = text.endIndex
    while j < end {
      let c = text[j]
      if c.isWhitespace {
        j = text.index(after: j)
        continue
      }
      if isSkippableSentenceBoundaryPunctuation(c) {
        j = text.index(after: j)
        continue
      }
      break
    }
    if j >= end {
      return nil
    }
    if isSentenceStartCharacter(text[j]) {
      return j
    }
    return nil
  }

  private func isSkippableSentenceBoundaryPunctuation(_ c: Character) -> Bool {
    // Characters that commonly appear between the terminator and the next sentence start.
    switch c {
    case "\"", "'", "”", "’", ")", "]", "}":
      return true
    default:
      return false
    }
  }

  private func isSentenceStartCharacter(_ c: Character) -> Bool {
    // Conservative: only split when the next sentence looks like it starts with a capital letter.
    let s = String(c)
    return s.rangeOfCharacter(from: .uppercaseLetters) != nil
  }

  private func shouldSplitAfter(wordBeforeTerminatorAt terminatorIndex: String.Index, in text: String, terminator: Character) -> Bool {
    if terminator != "." {
      return true
    }

    let word = lastWord(before: terminatorIndex, in: text)
    if word.isEmpty {
      return true
    }

    let abbreviations: Set<String> = [
      "Mr", "Mrs", "Ms", "Dr", "Prof", "Sr", "Jr", "St", "Mt", "Gen", "Rep", "Sen", "Gov"
    ]

    if abbreviations.contains(word) {
      return false
    }

    if word.count == 1 {
      // Avoid aggressive splitting for single-letter initials ("A.") but allow the common pronoun case ("I.").
      return word == "I"
    }

    return true
  }

  private func lastWord(before index: String.Index, in text: String) -> String {
    var j = index
    while j > text.startIndex, text[text.index(before: j)].isWhitespace {
      j = text.index(before: j)
    }
    let wordEnd = j
    while j > text.startIndex {
      let prev = text[text.index(before: j)]
      let s = String(prev)
      if s.rangeOfCharacter(from: .letters) == nil {
        break
      }
      j = text.index(before: j)
    }
    if j < wordEnd {
      return String(text[j..<wordEnd])
    }
    return ""
  }

  private func locatorByAddingTextQuote(
    base: Locator,
    sourceText: String,
    highlightRange: Range<String.Index>,
    contextSnippetLength: Int
  ) -> Locator {
    let beforeStart = sourceText.index(
      highlightRange.lowerBound,
      offsetBy: -contextSnippetLength,
      limitedBy: sourceText.startIndex
    ) ?? sourceText.startIndex

    let afterEnd = sourceText.index(
      highlightRange.upperBound,
      offsetBy: contextSnippetLength,
      limitedBy: sourceText.endIndex
    ) ?? sourceText.endIndex

    let before = String(sourceText[beforeStart..<highlightRange.lowerBound])
    let highlight = String(sourceText[highlightRange])
    let after = String(sourceText[highlightRange.upperBound..<afterEnd])

    return base.copy(text: { t in
      t.before = before.isEmpty ? nil : before
      t.highlight = highlight
      t.after = after.isEmpty ? nil : after
    })
  }

  private func finalizeSentenceIndex(raw: [(text: String, locator: Locator)]) -> [SentenceEntry] {
    let totalChars = raw.reduce(0) { $0 + $1.text.count }
    if totalChars <= 0 {
      return []
    }

    var offset = 0
    var out: [SentenceEntry] = []
    out.reserveCapacity(raw.count)
    for (idx, item) in raw.enumerated() {
      let start = offset
      let len = item.text.count
      let end = start + len
      let progression = Double(start) / Double(totalChars)
      out.append(
        SentenceEntry(
          index: idx,
          start: start,
          end: end,
          text: item.text,
          progression: progression,
          locator: item.locator
        )
      )
      offset = end
    }
    return out
  }

  private func getSentencesForHref(_ href: String) async -> [SentenceEntry] {
    let key = normalizeHref(href)
    if let cached = sentenceIndexCache[key] {
      sentenceIndexAccess[key] = Date()
      return cached
    }

    let sentences = await buildSentenceIndex(href: key)
    cacheSentenceIndex(href: key, sentences: sentences)
    return sentences
  }

  private func parseHighlightStyle(_ style: NSDictionary?) -> HighlightStyleConfig {
    let defaultTint = UIColor.systemYellow.withAlphaComponent(0.55)
    let defaultIsActive = true

    guard let style = style else {
      return HighlightStyleConfig(tint: defaultTint, isActive: defaultIsActive)
    }

    let isActive: Bool
    if let b = style["isActive"] as? Bool {
      isActive = b
    } else if let n = style["isActive"] as? NSNumber {
      isActive = n.boolValue
    } else {
      isActive = defaultIsActive
    }

    let tintValue = style["tint"]
    if let tintString = tintValue as? String {
      return HighlightStyleConfig(tint: colorFromTintString(tintString) ?? defaultTint, isActive: isActive)
    }

    if let tintNSString = tintValue as? NSString {
      return HighlightStyleConfig(tint: colorFromTintString(tintNSString as String) ?? defaultTint, isActive: isActive)
    }

    return HighlightStyleConfig(tint: defaultTint, isActive: isActive)
  }

  private func colorFromTintString(_ tintString: String) -> UIColor? {
    var s = tintString.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.hasPrefix("#") {
      s.removeFirst()
    }
    if s.lowercased().hasPrefix("0x") {
      s = String(s.dropFirst(2))
    }

    // Accept RGB (RRGGBB) or ARGB (AARRGGBB)
    guard s.count == 6 || s.count == 8 else {
      return nil
    }

    guard let raw = UInt64(s, radix: 16) else {
      return nil
    }

    let argb: UInt64 = (s.count == 6) ? ((UInt64(0xFF) << 24) | raw) : (raw & 0xFFFFFFFF)
    let a = CGFloat((argb >> 24) & 0xFF) / 255.0
    let r = CGFloat((argb >> 16) & 0xFF) / 255.0
    let g = CGFloat((argb >> 8) & 0xFF) / 255.0
    let b = CGFloat(argb & 0xFF) / 255.0
    return UIColor(red: r, green: g, blue: b, alpha: a)
  }

  private func applyHighlightDecoration(locator: Locator, style: NSDictionary?) {
    guard let navigator = readerViewController?.navigator else { return }
    guard let decorable = navigator as? DecorableNavigator else { return }
    guard decorable.supports(decorationStyle: .highlight) else { return }

    let cfg = parseHighlightStyle(style)

    let decoration = Decoration(
      id: highlightDecorationId,
      locator: locator,
      style: .highlight(tint: cfg.tint, isActive: cfg.isActive)
    )

    decorable.apply(decorations: [decoration], in: highlightDecorationGroup)
  }

  @objc func clearHighlight() {
    guard let navigator = readerViewController?.navigator else { return }
    guard let decorable = navigator as? DecorableNavigator else { return }
    decorable.apply(decorations: [], in: highlightDecorationGroup)
  }

  /// Best-effort: navigates to the start of the requested range.
  @objc func highlightRange(href: String, startProgression: Double, endProgression: Double) {
    highlightRangeWithStyle(href: href, startProgression: startProgression, endProgression: endProgression, style: nil)
  }

  @objc func highlightRangeWithStyle(href: String, startProgression: Double, endProgression: Double, style: NSDictionary?) {
    Task { @MainActor [weak self] in
      guard let self = self else { return }
      guard let navigator = self.readerViewController?.navigator else {
        return
      }

      let startP = min(max(min(startProgression, endProgression), 0), 1)

      guard let locator = await ReaderService.locatorFromLocation(
        [
          "href": href,
          "type": "application/xhtml+xml",
          "locations": [
            "progression": startP
          ]
        ],
        readerViewController?.publication
      ) else {
        return
      }

      self.clearHighlight()
      self.applyHighlightDecoration(locator: locator, style: style)

      _ = await navigator.go(
        to: locator,
        options: .animated
      )
    }
  }

  @objc func highlightSentence(href: String, sentenceIndex: Int) {
    highlightSentenceWithStyle(href: href, sentenceIndex: sentenceIndex, style: nil)
  }

  @objc func highlightSentenceWithStyle(href: String, sentenceIndex: Int, style: NSDictionary?) {
    Task { @MainActor [weak self] in
      guard let self = self else { return }
      guard let navigator = self.readerViewController?.navigator else { return }

      let sentences = await self.getSentencesForHref(href)
      guard sentenceIndex >= 0, sentenceIndex < sentences.count else { return }

      let entry = sentences[sentenceIndex]
      self.clearHighlight()
      self.applyHighlightDecoration(locator: entry.locator, style: style)
      _ = await navigator.go(to: entry.locator, options: .animated)
    }
  }

  func getChapterSentencePage(
    href: String,
    offset: Int,
    limit: Int,
    completion: @escaping (_ total: Int, _ items: [[String: Any]]) -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self = self else { return }

      let sentences = await self.getSentencesForHref(href)
      let total = sentences.count
      let safeOffset = max(0, min(offset, total))
      let safeLimit = max(0, limit)

      let slice = safeLimit == 0 ? [] : Array(sentences.dropFirst(safeOffset).prefix(safeLimit))
      let items = slice.map { s in
        [
          "index": s.index,
          "progression": s.progression,
          "text": s.text,
        ]
      }

      completion(total, items)
    }
  }

  func getChapterSentences(
    href: String,
    completion: @escaping (_ sentences: [String]) -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self = self else { return }
      let sentences = await self.getSentencesForHref(href)
      completion(sentences.map { $0.text })
    }
  }

  func getSentenceIndexFromProgression(
    href: String,
    progression: Double,
    completion: @escaping (_ index: Int) -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self = self else { return }

      let sentences = await self.getSentencesForHref(href)
      if sentences.isEmpty {
        completion(0)
        return
      }

      let p = max(0.0, min(1.0, progression))
      let totalChars = sentences.last?.end ?? 0
      if totalChars <= 0 {
        completion(0)
        return
      }

      let targetChar = min(max(Int((Double(totalChars) * p).rounded()), 0), totalChars)

      var lo = 0
      var hi = sentences.count - 1
      while lo < hi {
        let mid = (lo + hi) / 2
        let s = sentences[mid]
        if targetChar < s.start {
          hi = mid
        } else if targetChar >= s.end {
          lo = mid + 1
        } else {
          lo = mid
          break
        }
      }

      completion(sentences[lo].index)
    }
  }

  func highlightSentenceFromProgression(
    href: String,
    progression: Double,
    completion: @escaping (_ index: Int) -> Void
  ) {
    highlightSentenceFromProgression(href: href, progression: progression, style: nil, completion: completion)
  }

  func highlightSentenceFromProgression(
    href: String,
    progression: Double,
    style: NSDictionary?,
    completion: @escaping (_ index: Int) -> Void
  ) {
    getSentenceIndexFromProgression(href: href, progression: progression) { [weak self] index in
      self?.highlightSentenceWithStyle(href: href, sentenceIndex: index, style: style)
      completion(index)
    }
  }

  func updatePreferences(_ preferences: NSString?) {

    if (readerViewController == nil) {
      // defer setting update as view isn't initialized yet
      return;
    }

    guard let navigator = readerViewController!.navigator as? EPUBNavigatorViewController else {
      return;
    }

    guard let preferencesJson = preferences as? String else {
      print("TODO: handle error. Bad string conversion for preferences")
      return;
    }

    do {
      let preferences = try JSONDecoder().decode(EPUBPreferences.self, from: Data(preferencesJson.utf8))
      navigator.submitPreferences(preferences)
    } catch {
      print(error)
      print("TODO: handle error. Skipping preferences due to thrown exception")
      return;
    }
  }

  override func removeFromSuperview() {
    readerViewController?.willMove(toParent: nil)
    readerViewController?.view.removeFromSuperview()
    readerViewController?.removeFromParent()

    // cancel all current subscriptions
    for subscription in subscriptions {
      subscription.cancel()
    }
    subscriptions = Set<AnyCancellable>()

    readerViewController = nil
    super.removeFromSuperview()
  }

  private func addViewControllerAsSubview(_ vc: ReaderViewController) {
    vc.publisher.sink(
      receiveValue: { locator in
        self.onLocationChange?(locator.json)
      }
    )
    .store(in: &self.subscriptions)

    readerViewController = vc
    readerViewController?.onTap = { [weak self] point in
      self?.onTap?([
        "x": point.x,
        "y": point.y
      ])
    }

    // if the controller was just instantiated then apply any existing preferences
    if (preferences != nil) {
      self.updatePreferences(preferences)
    }


    guard
      readerViewController != nil,
      superview?.frame != nil,
      self.viewController != nil,
      self.readerViewController != nil
    else {
      return
    }

    readerViewController!.view.frame = superview!.frame
    self.viewController!.addChild(readerViewController!)
    let rootView = self.readerViewController!.view!
    self.addSubview(rootView)
    self.viewController!.addChild(readerViewController!)
    self.readerViewController!.didMove(toParent: self.viewController!)

    // bind the reader's view to be constrained to its parent
    rootView.translatesAutoresizingMaskIntoConstraints = false
    rootView.topAnchor.constraint(equalTo: self.topAnchor).isActive = true
    rootView.bottomAnchor.constraint(equalTo: self.bottomAnchor).isActive = true
    rootView.leftAnchor.constraint(equalTo: self.leftAnchor).isActive = true
    rootView.rightAnchor.constraint(equalTo: self.rightAnchor).isActive = true

    Task { @MainActor [weak self] in
      guard let self = self else { return }

      // Always fetch table of contents and positions, regardless of callback existence
      // This matches Android behavior and ensures data is loaded consistently
      let tocResult = await vc.publication.tableOfContents()
      let positionsResult = await vc.publication.positions()

      var payload: [String: Any] = [:]

      // Add table of contents
      switch tocResult {
      case .success(let links):
        payload["tableOfContents"] = links.map { $0.json }
      case .failure(let error):
        self.log(.error, "Failed to fetch table of contents: \(error)")
        payload["tableOfContents"] = []
      }

      // Add positions
      switch positionsResult {
      case .success(let positions):
        payload["positions"] = positions.map { $0.json }
      case .failure(let error):
        self.log(.error, "Failed to fetch positions: \(error)")
        payload["positions"] = []
      }

      // Add metadata
      // Note: Swift Readium library's .json property already normalizes LocalizedStrings
      // to plain strings, so no additional normalization is needed here.
      // This matches the RWPM spec's shorthand format and is consistent with our
      // normalization on Android and Web platforms.
      payload["metadata"] = vc.publication.metadata.json

      // Always emit onPublicationReady event
      // React Native bridge handles null callbacks gracefully
      self.onPublicationReady?(payload)
    }
  }
}
