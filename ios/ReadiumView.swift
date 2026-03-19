import Combine
import Foundation
import ReadiumShared
import ReadiumStreamer
import UIKit
import ReadiumNavigator


private extension Array {
  subscript(safe index: Int) -> Element? {
    guard index >= 0 && index < count else { return nil }
    return self[index]
  }
}

private extension Comparable {
  func clamped(to limits: ClosedRange<Self>) -> Self {
    return min(max(self, limits.lowerBound), limits.upperBound)
  }
}

private struct ReaderAppearancePreferences: Decodable {
  let backgroundColor: String?
  let textColor: String?
}

private extension Dictionary where Key == String, Value == Any {
  mutating func adaptReadiumColorPreference(_ key: String) {
    guard let cssColor = self[key] as? String else { return }
    guard let uiColor = UIColor.readiumColor(from: cssColor) else { return }
    guard let readiumColor = Color(uiColor: uiColor) else { return }
    self[key] = readiumColor.rawValue
  }
}

private extension Data {
  func adaptingReadiumColorPreferences() -> Data {
    guard
      let jsonObject = try? JSONSerialization.jsonObject(with: self),
      var json = jsonObject as? [String: Any]
    else {
      return self
    }

    json.adaptReadiumColorPreference("backgroundColor")
    json.adaptReadiumColorPreference("textColor")

    guard let adapted = try? JSONSerialization.data(withJSONObject: json) else {
      return self
    }

    return adapted
  }
}

private extension UIColor {
  static func readiumColor(from cssColor: String?) -> UIColor? {
    guard let cssColor else { return nil }

    let trimmed = cssColor.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return nil }

    let normalized = trimmed.hasPrefix("#") ? String(trimmed.dropFirst()) : trimmed
    let hex: String

    switch normalized.count {
    case 3:
      hex = normalized.map { "\($0)\($0)" }.joined()
    case 6, 8:
      hex = normalized
    default:
      return nil
    }

    var value: UInt64 = 0
    guard Scanner(string: hex).scanHexInt64(&value) else { return nil }

    if hex.count == 8 {
      return UIColor(
        red: CGFloat((value & 0xFF000000) >> 24) / 255.0,
        green: CGFloat((value & 0x00FF0000) >> 16) / 255.0,
        blue: CGFloat((value & 0x0000FF00) >> 8) / 255.0,
        alpha: CGFloat(value & 0x000000FF) / 255.0
      )
    }

    return UIColor(
      red: CGFloat((value & 0xFF0000) >> 16) / 255.0,
      green: CGFloat((value & 0x00FF00) >> 8) / 255.0,
      blue: CGFloat(value & 0x0000FF) / 255.0,
      alpha: 1.0
    )
  }
}


class ReadiumView : UIView, Loggable {
  var readerService: ReaderService = ReaderService()
  var readerViewController: ReaderViewController?
  var viewController: UIViewController? {
    let viewController = sequence(first: self, next: { $0.next }).first(where: { $0 is UIViewController })
    return viewController as? UIViewController
  }
  private var subscriptions = Set<AnyCancellable>()
  private let viewportTextExtractor = ViewportTextExtractor()
  private typealias SentenceEntry = SentenceIndexStore.SentenceEntry
  private let sentenceIndexStore = SentenceIndexStore()
  private var loadRequestID: Int = 0
  private var allowedHrefsSet: Set<String>? = nil
  private var lastKnownHref: String? = nil
  private var publicationPositions: [Locator] = []
  private var restrictedAnchorLocator: Locator? = nil
  private var activeRestrictedHref: String? = nil
  private var pendingRestrictedTargetHref: String? = nil

  private var publicationPositionsByHref: [String: [Double]] = [:]
  private var publicationProgressionByPosition: [Int: (href: String, progression: Double, totalProgression: Double?)] = [:]
  private var publicationPositionEntriesByHref: [String: [(position: Int, progression: Double?, totalProgression: Double?)]] = [:]
  private var publicationPositionEntriesByProgressionByHref: [String: [(progression: Double, position: Int, totalProgression: Double?)]] = [:]

  private func resolveHrefProgressionFromPosition(hrefKey: String, position: Int) -> Double? {
    guard let entries = publicationPositionEntriesByHref[hrefKey], !entries.isEmpty else {
      return nil
    }

    guard let idx = entries.firstIndex(where: { $0.position == position }) else {
      return nil
    }

    if let p = entries[idx].progression {
      return p
    }

    // Try to interpolate between known progression neighbors.
    var prevIdx: Int? = nil
    var nextIdx: Int? = nil

    if idx > 0 {
      for i in stride(from: idx - 1, through: 0, by: -1) {
        if entries[i].progression != nil {
          prevIdx = i
          break
        }
      }
    }
    if idx + 1 < entries.count {
      for i in (idx + 1)..<entries.count {
        if entries[i].progression != nil {
          nextIdx = i
          break
        }
      }
    }

    if let pI = prevIdx, let nI = nextIdx,
       let p0 = entries[pI].progression, let p1 = entries[nI].progression,
       nI > pI {
      let t = Double(idx - pI) / Double(nI - pI)
      return p0 + (p1 - p0) * t
    }
    if let pI = prevIdx, let p0 = entries[pI].progression {
      return p0
    }
    if let nI = nextIdx, let p1 = entries[nI].progression {
      return p1
    }

    // As a last resort, approximate with the relative index within this href.
    if entries.count <= 1 { return 0.0 }
    return Double(idx) / Double(entries.count - 1)
  }

  private func resolvePositionFromProgression(hrefKey: String, progression: Double) -> Int? {
    let p = min(max(progression, 0.0), 1.0)
    let eps = 1e-9

    if let byProg = publicationPositionEntriesByProgressionByHref[hrefKey], !byProg.isEmpty {
      // Upper bound on progression (last entry with progression <= p).
      var lo = 0
      var hi = byProg.count
      while lo < hi {
        let mid = (lo + hi) / 2
        if byProg[mid].progression <= p + eps {
          lo = mid + 1
        } else {
          hi = mid
        }
      }
      let idx = max(0, lo - 1)
      return byProg[idx].position
    }

    // Fallback: approximate by index within this href.
    guard let entries = publicationPositionEntriesByHref[hrefKey], !entries.isEmpty else {
      return nil
    }
    if entries.count == 1 { return entries[0].position }
    let approxIdx = Int((p * Double(entries.count - 1)).rounded(.down))
    let clamped = min(max(approxIdx, 0), entries.count - 1)
    return entries[clamped].position
  }

  private func resolveTotalProgressionFromProgression(hrefKey: String, progression: Double) -> Double? {
    let p = min(max(progression, 0.0), 1.0)
    let eps = 1e-9
    guard let byProg = publicationPositionEntriesByProgressionByHref[hrefKey], !byProg.isEmpty else {
      return nil
    }

    // Upper bound on progression.
    var lo = 0
    var hi = byProg.count
    while lo < hi {
      let mid = (lo + hi) / 2
      if byProg[mid].progression <= p + eps {
        lo = mid + 1
      } else {
        hi = mid
      }
    }
    var idx = max(0, lo - 1)

    if let tp = byProg[idx].totalProgression { return tp }

    // Best-effort: search nearest neighbor with totalProgression.
    var left = idx - 1
    var right = idx + 1
    while left >= 0 || right < byProg.count {
      if left >= 0, let tp = byProg[left].totalProgression { return tp }
      if right < byProg.count, let tp = byProg[right].totalProgression { return tp }
      left -= 1
      right += 1
    }
    return nil
  }

  private func rewrittenSentenceLocatorJson(_ entry: SentenceEntry, hrefKey: String) -> [String: Any] {
    var json = entry.locator.json
    var locations = (json["locations"] as? [String: Any]) ?? [:]

    // Per-sentence progression must be stable and not snapped to segment locators.
    locations["progression"] = min(max(entry.progression, 0.0), 1.0)

    if let pos = resolvePositionFromProgression(hrefKey: hrefKey, progression: entry.progression) {
      locations["position"] = pos
    }
    if let tp = resolveTotalProgressionFromProgression(hrefKey: hrefKey, progression: entry.progression) {
      locations["totalProgression"] = tp
    }

    json["locations"] = locations
    return json
  }

  private func ensurePositionsCache() async {
    if !publicationPositionsByHref.isEmpty && !publicationPositionEntriesByHref.isEmpty { return }
    guard let publication = readerViewController?.publication else { return }

    let positionsResult = await publication.positions()
    if case .success(let positions) = positionsResult {
      publicationPositions = positions
      recomputeRestrictionAnchorLocator()

      var byHref: [String: [Double]] = [:]
      var entriesByHref: [String: [(position: Int, progression: Double?, totalProgression: Double?)]] = [:]
      var entriesByProgressionByHref: [String: [(progression: Double, position: Int, totalProgression: Double?)]] = [:]
      for loc in positions {
        let k = normalizedHrefForComparisonAnyURL(loc.href)
        let prog = loc.locations.progression
        let totalProg = loc.locations.totalProgression
        if let prog {
          byHref[k, default: []].append(prog)
        }

        if let pos = loc.locations.position {
          entriesByHref[k, default: []].append((position: pos, progression: prog, totalProgression: totalProg))
          if let prog {
            publicationProgressionByPosition[pos] = (href: k, progression: prog, totalProgression: totalProg)
            entriesByProgressionByHref[k, default: []].append((progression: prog, position: pos, totalProgression: totalProg))
          }
        }
      }
      for (k, list) in byHref {
        byHref[k] = Array(Set(list)).sorted()
      }
      publicationPositionsByHref = byHref
      for (k, list) in entriesByHref {
        entriesByHref[k] = list.sorted { $0.position < $1.position }
      }
      publicationPositionEntriesByHref = entriesByHref

      for (k, list) in entriesByProgressionByHref {
        entriesByProgressionByHref[k] = list.sorted { $0.progression < $1.progression }
      }
      publicationPositionEntriesByProgressionByHref = entriesByProgressionByHref
    }
  }

  func getVisibleTextRangePayload(includeText: Bool, maxTextLength: Int?, source: String?) async throws -> [String: Any] {
    guard let navigator = readerViewController?.navigator else {
      throw NSError(domain: "readium", code: 1, userInfo: [NSLocalizedDescriptionKey: "Reader not ready"])
    }

    guard let current = navigator.currentLocation else {
      throw NSError(domain: "readium", code: 2, userInfo: [NSLocalizedDescriptionKey: "Current location not available"])
    }

    let hrefKey = normalizedHrefForComparisonAnyURL(current.href)

    await ensurePositionsCache()

    let pResolved: Double = {
      if let p = current.locations.progression { return p }
      if let pos = current.locations.position,
         let entry = publicationProgressionByPosition[pos],
         entry.href == hrefKey {
        return entry.progression
      }
      if let pos = current.locations.position,
         let p = resolveHrefProgressionFromPosition(hrefKey: hrefKey, position: pos) {
        return p
      }
      return 0.0
    }()
    let p = max(0.0, min(1.0, pResolved))

    let boundaries = publicationPositionsByHref[hrefKey] ?? []
    let EPS = 1e-9

    func pageRangeFromPosition() -> (Double, Double)? {
      guard let entries = publicationPositionEntriesByHref[hrefKey], !entries.isEmpty else { return nil }
      return PublicationPositionResolver.pageRangeFromPosition(
        position: current.locations.position,
        entries: entries.map { (position: $0.position, progression: $0.progression) }
      )
    }

    let pageStart: Double
    let pageEnd: Double

    if let (s, e) = pageRangeFromPosition() {
      pageStart = s
      pageEnd = e
    } else {
      let bIdx = PublicationPositionResolver.boundaryIndex(for: p, in: boundaries, eps: EPS)
      pageStart = boundaries.isEmpty ? 0.0 : (boundaries[safe: bIdx] ?? 0.0)
      pageEnd = boundaries.isEmpty ? 1.0 : (boundaries[safe: bIdx + 1] ?? 1.0)
    }

    func sanitizeVisibleTextForJs(_ text: String) -> String {
      if text.isEmpty { return text }
      // Keep character counts stable while removing disruptive whitespace characters.
      return text
        .replacingOccurrences(of: "\r", with: " ")
        .replacingOccurrences(of: "\n", with: " ")
        .replacingOccurrences(of: "\t", with: " ")
    }

    let sourceNorm = source?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    if sourceNorm == "viewport" {
      let viewport = await viewportTextExtractor.extract(from: readerViewController?.view ?? self)
      if let viewport = viewport {
      let totalChars = viewport.totalChars
      let start = viewport.start
      let end = viewport.end

      var payload: [String: Any] = [
        "href": hrefKey,
        "start": start,
        "end": end,
        "totalChars": totalChars,
        "rangeSource": "viewport"
      ]

      if includeText {
        let fullText = sanitizeVisibleTextForJs(viewport.text)
        let available = max(0, end - start)
        let maxLen = (maxTextLength ?? Int.max)
        let take = max(0, min(available, maxLen))
        let text = take <= 0 ? "" : String(fullText.prefix(take))
        payload["text"] = text
        payload["isTruncated"] = take < available
      }

        if let pos = current.locations.position {
          payload["position"] = pos
        }

        return payload
      }

      // Explicitly requested viewport extraction but it failed.
      // Do NOT reject; fall back to approx while surfacing diagnostics.
      // This keeps behavior aligned with Android (best-effort result).
    }

    let sentences = await getSentencesForHref(hrefKey)
    let totalChars = sentences.last?.end ?? 0

    // Select sentences by progression interval instead of matching pageStartProgression exactly.
    // This stays robust even when the page boundary comes from `locations.position`.
    let inPage = sentences
      .filter { $0.progression >= pageStart - EPS && $0.progression < pageEnd - EPS }
      .sorted { $0.start < $1.start }

    var start = (inPage.first?.start ?? 0).clamped(to: 0...max(totalChars, 0))
    var end = (inPage.last?.end ?? totalChars).clamped(to: start...max(totalChars, start))

    if totalChars > 0 {
      let approxStart = Int((Double(totalChars) * pageStart).rounded(.down))
      let approxEnd = Int((Double(totalChars) * pageEnd).rounded(.up))
      let clampStart = approxStart.clamped(to: 0...totalChars)
      let clampEnd = approxEnd.clamped(to: clampStart...totalChars)
      if clampEnd > clampStart {
        let s = max(start, clampStart)
        let e = min(end, clampEnd)
        if e > s {
          start = s
          end = e
        }
      }
    }

    var payload: [String: Any] = [
      "href": hrefKey,
      "start": start,
      "end": end,
      "totalChars": totalChars,
      "rangeSource": "approx",
    ]

    if sourceNorm == "viewport" {
      payload["requestedRangeSource"] = "viewport"
      if let reason = viewportTextExtractor.lastFailureReason {
        payload["viewportFailureReason"] = reason
      }
    }

    if includeText {
      let maxLen = (maxTextLength ?? Int.max)
      let take = max(0, min(end - start, maxLen))
      let text = inPage.map { $0.text }.joined()
      if take < text.count {
        let i = text.index(text.startIndex, offsetBy: take)
        payload["text"] = sanitizeVisibleTextForJs(String(text[..<i]))
        payload["isTruncated"] = true
      } else {
        payload["text"] = sanitizeVisibleTextForJs(text)
        payload["isTruncated"] = false
      }
    }

    if let pos = current.locations.position {
      payload["position"] = pos
    }

    return payload
  }

  private let highlightDecorationGroup = "rn_highlight"
  private let highlightDecorationId = "active"

  @objc var file: NSDictionary? = nil {
    didSet {
      sentenceIndexStore.clear()
      publicationPositionsByHref.removeAll()
      publicationPositionEntriesByHref.removeAll()
      publicationPositionEntriesByProgressionByHref.removeAll()
      publicationProgressionByPosition.removeAll()
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
      sentenceIndexStore.clear()
      publicationPositionsByHref.removeAll()
      publicationPositionEntriesByHref.removeAll()
      publicationPositionEntriesByProgressionByHref.removeAll()
      publicationProgressionByPosition.removeAll()
      self.updatePreferences(preferences)
    }
  }
  @objc var allowedHrefs: NSString? = nil {
    didSet {
      allowedHrefsSet = parseAllowedHrefs(allowedHrefs)
      recomputeRestrictionAnchorLocator()
      reevaluateRestrictionForCurrentHref()
      reloadBookIfNeeded()
    }
  }
  @objc var paywallHTML: NSString? = nil {
    didSet {
      reloadBookIfNeeded()
    }
  }

  @objc var hidePageNumbers: Bool = false {
    didSet {
      updatePageNumberVisibility()
    }
  }
  @objc var enableTapNavigation: Bool = true {
    didSet {
      readerViewController?.enableTapNavigation = enableTapNavigation
    }
  }
  @objc var disableTextSelection: Bool = false {
    didSet {
      readerViewController?.disableTextSelection = disableTextSelection
    }
  }

  @objc var onLocationChange: RCTDirectEventBlock?
  @objc var onPublicationReady: RCTDirectEventBlock?
  @objc var onRestrictedNavigation: RCTDirectEventBlock?
  @objc var onTap: RCTDirectEventBlock?

  func loadBook(
    url: String,
    location: NSDictionary?
  ) {
    guard let rootViewController = UIApplication.shared.delegate?.window??.rootViewController else { return }

    loadRequestID += 1
    let requestID = loadRequestID
    detachReaderViewControllerIfNeeded()

    self.readerService.buildViewController(
      url: url,
      bookId: url,
      location: location,
      restriction: currentRestrictionConfiguration(),
      sender: rootViewController,
      completion: { vc in
        guard self.loadRequestID == requestID else {
          return
        }
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
      guard let requestedLocator = await self.getLocator() else {
        return
      }

      let locator = self.mapRequestedLocatorToRestrictionAnchor(requestedLocator)

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

  private func reloadBookIfNeeded() {
    guard let initialLocation = file?["initialLocation"] as? NSDictionary,
          let url = file?["url"] as? String else {
      if let url = file?["url"] as? String {
        loadBook(url: url, location: nil)
      }
      return
    }

    loadBook(url: url, location: initialLocation)
  }

  private func detachReaderViewControllerIfNeeded() {
    subscriptions.removeAll()
    publicationPositions = []
    restrictedAnchorLocator = nil
    lastKnownHref = nil
    activeRestrictedHref = nil
    pendingRestrictedTargetHref = nil
    guard let readerViewController else {
      return
    }

    readerViewController.willMove(toParent: nil)
    readerViewController.view.removeFromSuperview()
    readerViewController.removeFromParent()
    self.readerViewController = nil
  }

  private func normalizeHref(_ href: String) -> String {
    return href.trimmingCharacters(in: .whitespacesAndNewlines)
      .replacingOccurrences(of: "^/+", with: "", options: .regularExpression)
  }

  private func parseAllowedHrefs(_ raw: NSString?) -> Set<String>? {
    guard let raw = raw as String? else {
      return nil
    }

    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty {
      return nil
    }

    guard let data = trimmed.data(using: .utf8) else {
      return nil
    }

    do {
      let parsed = try JSONSerialization.jsonObject(with: data, options: [])
      guard let hrefs = parsed as? [String] else {
        return nil
      }
      return Set(hrefs.map { normalizedHrefForComparison($0) })
    } catch {
      log(.warning, "Failed to parse allowedHrefs: \(error)")
      return nil
    }
  }

  private func normalizedHrefForComparison(_ href: String) -> String {
    let base = normalizeHref(href)
    let noFragment = base.components(separatedBy: "#").first ?? base
    let noQuery = noFragment.components(separatedBy: "?").first ?? noFragment
    return noQuery.removingPercentEncoding ?? noQuery
  }

  private func normalizedHrefForComparisonAnyURL(_ href: AnyURL) -> String {
    return normalizedHrefForComparison(normalizeHrefAnyURL(href))
  }

  private func normalizeHrefAnyURL(_ href: AnyURL) -> String {
    // Readium 3.x uses `AnyURL` for Locator.href
    return normalizeHref(href.url.relativeString)
  }

  private func isHrefRestricted(_ href: String) -> Bool {
    guard let allowed = allowedHrefsSet else {
      return false
    }

    return !allowed.contains(href)
  }

  private func currentRestrictionConfiguration() -> PublicationRestrictionConfiguration? {
    guard let allowedHrefsSet else {
      return nil
    }

    return PublicationRestrictionConfiguration(
      allowedHrefs: allowedHrefsSet,
      paywallHTML: paywallHTML as String?
    )
  }

  private func recomputeRestrictionAnchorLocator() {
    guard let allowed = allowedHrefsSet, !publicationPositions.isEmpty else {
      restrictedAnchorLocator = nil
      return
    }

    restrictedAnchorLocator = publicationPositions.first(where: {
      !allowed.contains(normalizedHrefForComparisonAnyURL($0.href))
    })
  }

  private func mapRequestedLocatorToRestrictionAnchor(_ requested: Locator) -> Locator {
    let requestedHref = normalizedHrefForComparisonAnyURL(requested.href)
    if !isHrefRestricted(requestedHref) {
      pendingRestrictedTargetHref = nil
      return requested
    }

    pendingRestrictedTargetHref = requestedHref
    return restrictedAnchorLocator ?? requested
  }

  private func emitRestrictedNavigation(_ href: String) {
    onRestrictedNavigation?([
      "href": href,
    ])
  }

  private func activateRestrictedNavigationIfNeeded(href: String) {
    guard activeRestrictedHref != href else {
      return
    }

    activeRestrictedHref = href
    emitRestrictedNavigation(href)
  }

  private func clearRestrictedNavigationIfNeeded() {
    guard activeRestrictedHref != nil else {
      return
    }

    activeRestrictedHref = nil
    pendingRestrictedTargetHref = nil
    emitRestrictedNavigation("")
  }

  private func handleLocatorAccess(_ locator: Locator) {
    let href = normalizedHrefForComparisonAnyURL(locator.href)
    lastKnownHref = href

    if isHrefRestricted(href) {
      activateRestrictedNavigationIfNeeded(href: pendingRestrictedTargetHref ?? href)
    } else {
      clearRestrictedNavigationIfNeeded()
    }
  }

  private func reevaluateRestrictionForCurrentHref() {
    let href = lastKnownHref ?? readerViewController?.navigator.currentLocation.map {
      normalizedHrefForComparisonAnyURL($0.href)
    }

    guard let href else {
      clearRestrictedNavigationIfNeeded()
      return
    }

    lastKnownHref = href
    if isHrefRestricted(href) {
      activateRestrictedNavigationIfNeeded(href: pendingRestrictedTargetHref ?? href)
    } else {
      clearRestrictedNavigationIfNeeded()
    }
  }

  private func getSentencesForHref(_ href: String) async -> [SentenceEntry] {
    await ensurePositionsCache()
    guard let publication = readerViewController?.publication else { return [] }
    return await sentenceIndexStore.getSentencesForHref(
      href: href,
      publication: publication,
      normalizeHref: normalizeHref,
      normalizedHrefForComparison: normalizedHrefForComparison,
      positionsByHref: publicationPositionsByHref
    )
  }


  private func applyHighlightDecoration(locator: Locator, style: NSDictionary?) {
    guard let navigator = readerViewController?.navigator else { return }
    guard let decorable = navigator as? DecorableNavigator else { return }
    guard decorable.supports(decorationStyle: .highlight) else { return }

    let cfg = HighlightStyleParser.parse(style: style)

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

  /// Applies a highlight decoration to the requested range.
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
    }
  }

  @objc func highlightLocator(location: NSDictionary) {
    highlightLocatorWithStyle(location: location, style: nil)
  }

  @objc func highlightLocatorWithStyle(location: NSDictionary, style: NSDictionary?) {
    Task { @MainActor [weak self] in
      guard let self = self else { return }
      guard let navigator = self.readerViewController?.navigator else { return }
      _ = navigator // Silence unused warning; we only gate on readiness here.

      guard let locator = await ReaderService.locatorFromLocation(
        location,
        readerViewController?.publication
      ) else {
        return
      }

      self.clearHighlight()
      self.applyHighlightDecoration(locator: locator, style: style)
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

      let hrefKey = self.normalizedHrefForComparison(href)

      let slice = safeLimit == 0 ? [] : Array(sentences.dropFirst(safeOffset).prefix(safeLimit))
      let items = slice.map { s in
        [
          "index": s.index,
          "text": s.text,
          "locator": self.rewrittenSentenceLocatorJson(s, hrefKey: hrefKey),
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
      let EPS = 1e-9

      let hrefKey = normalizedHrefForComparison(href)
      let boundaries = publicationPositionsByHref[hrefKey] ?? []

      let bIdx = PublicationPositionResolver.boundaryIndex(for: p, in: boundaries, eps: EPS)
      let pageStart = boundaries.isEmpty ? 0.0 : (boundaries[safe: bIdx] ?? 0.0)
      let pageEnd = boundaries.isEmpty ? 1.0 : (boundaries[safe: bIdx + 1] ?? 1.0)

      let inPage = sentences
        .filter { abs($0.pageStartProgression - pageStart) <= EPS }
        .sorted { $0.progression < $1.progression }

      if inPage.isEmpty {
        completion(sentences.first!.index)
        return
      }

      // Exact page boundary => first sentence on that page.
      if p <= pageStart + EPS {
        completion(inPage.first!.index)
        return
      }

      // Otherwise floor within the page.
      var resolved = inPage.first!.index
      for s in inPage {
        if s.progression <= p + EPS && s.progression < pageEnd + EPS {
          resolved = s.index
        } else {
          break
        }
      }
      completion(resolved)
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
      let preferencesData = Data(preferencesJson.utf8)
      let navigatorPreferencesData = preferencesData.adaptingReadiumColorPreferences()
      let preferences = try JSONDecoder().decode(EPUBPreferences.self, from: navigatorPreferencesData)
      navigator.submitPreferences(preferences)

      let appearance = try? JSONDecoder().decode(
        ReaderAppearancePreferences.self,
        from: preferencesData
      )
      readerViewController?.applyReaderColors(
        backgroundColor: UIColor.readiumColor(from: appearance?.backgroundColor),
        textColor: UIColor.readiumColor(from: appearance?.textColor)
      )
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

  private func updatePageNumberVisibility() {
    readerViewController?.setPositionLabelHidden(hidePageNumbers)
  }

  private func addViewControllerAsSubview(_ vc: ReaderViewController) {
    vc.publisher.sink(
      receiveValue: { locator in
        self.handleLocatorAccess(locator)
        self.onLocationChange?(locator.json)
      }
    )
    .store(in: &self.subscriptions)

    readerViewController = vc
    readerViewController?.enableTapNavigation = enableTapNavigation
    readerViewController?.disableTextSelection = disableTextSelection
    readerViewController?.onTap = { [weak self] point in
      self?.onTap?([
        "x": point.x,
        "y": point.y
      ])
    }

    updatePageNumberVisibility()

    // if the controller was just instantiated then apply any existing preferences
    if (preferences != nil) {
      self.updatePreferences(preferences)
    }

    reevaluateRestrictionForCurrentHref()


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
        self.publicationPositions = positions
        self.recomputeRestrictionAnchorLocator()

        // Cache per-href discrete position boundaries for sentence progression snapping.
        var byHref: [String: [Double]] = [:]
        for loc in positions {
          guard let p = loc.locations.progression else { continue }
          let key = self.normalizedHrefForComparisonAnyURL(loc.href)
          byHref[key, default: []].append(p)
        }
        for (k, list) in byHref {
          byHref[k] = Array(Set(list)).sorted()
        }
        self.publicationPositionsByHref = byHref
      case .failure(let error):
        self.log(.error, "Failed to fetch positions: \(error)")
        payload["positions"] = []
        self.publicationPositionsByHref = [:]
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
