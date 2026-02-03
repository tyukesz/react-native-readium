import Foundation
import ReadiumShared
import ReadiumNavigator

final class SentenceIndexStore {
  struct SentenceEntry {
    let index: Int
    let start: Int
    let end: Int
    let text: String
    let progression: Double
    let pageStartProgression: Double
    let locator: Locator
  }

  private var cache: [String: [SentenceEntry]] = [:]
  private var access: [String: Date] = [:]
  private let maxEntries = 4

  func clear() {
    cache.removeAll()
    access.removeAll()
  }

  func getSentencesForHref(
    href: String,
    publication: Publication,
    normalizeHref: (String) -> String,
    normalizedHrefForComparison: (String) -> String,
    positionsByHref: [String: [Double]]
  ) async -> [SentenceEntry] {
    let key = normalizeHref(href)
    if let cached = cache[key] {
      access[key] = Date()
      return cached
    }

    let sentences = await buildSentenceIndex(
      href: key,
      publication: publication,
      normalizeHref: normalizeHref,
      normalizedHrefForComparison: normalizedHrefForComparison,
      positionsByHref: positionsByHref
    )
    cacheSentenceIndex(href: key, sentences: sentences)
    return sentences
  }

  private func cacheSentenceIndex(href: String, sentences: [SentenceEntry]) {
    cache[href] = sentences
    access[href] = Date()

    if cache.count > maxEntries {
      if let oldest = access.min(by: { $0.value < $1.value })?.key {
        cache.removeValue(forKey: oldest)
        access.removeValue(forKey: oldest)
      }
    }
  }

  private func buildSentenceIndex(
    href: String,
    publication: Publication,
    normalizeHref: (String) -> String,
    normalizedHrefForComparison: (String) -> String,
    positionsByHref: [String: [Double]]
  ) async -> [SentenceEntry] {
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
            let segmentHref = normalizeHref(segment.locator.href.url.relativeString)
            if segmentHref != href {
              if started {
                // We have moved past the requested resource.
                return finalizeSentenceIndex(
                  raw: raw,
                  normalizedHrefForComparison: normalizedHrefForComparison,
                  positionsByHref: positionsByHref
                )
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
      // No-op: fall through to finalize whatever we have.
    }

    return finalizeSentenceIndex(
      raw: raw,
      normalizedHrefForComparison: normalizedHrefForComparison,
      positionsByHref: positionsByHref
    )
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
    switch c {
    case "\"", "'", "”", "’", ")", "]", "}":
      return true
    default:
      return false
    }
  }

  private func isSentenceStartCharacter(_ c: Character) -> Bool {
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

  private func finalizeSentenceIndex(
    raw: [(text: String, locator: Locator)],
    normalizedHrefForComparison: (String) -> String,
    positionsByHref: [String: [Double]]
  ) -> [SentenceEntry] {
    let totalChars = raw.reduce(0) { $0 + $1.text.count }
    if totalChars <= 0 {
      return []
    }

    let hrefKey = raw.first.map { normalizedHrefForComparison($0.locator.href.url.relativeString) } ?? ""
    let positionBoundaries = positionsByHref[hrefKey] ?? []

    func findBoundaryIndex(_ p: Double) -> Int {
      guard !positionBoundaries.isEmpty else { return 0 }
      var idx = 0
      for i in positionBoundaries.indices {
        if positionBoundaries[i] <= p + 1e-9 {
          idx = i
        } else {
          break
        }
      }
      return idx
    }

    struct Draft {
      let idx: Int
      let start: Int
      let end: Int
      let text: String
      let locator: Locator
      let boundaryIndex: Int
    }

    var offset = 0
    var drafts: [Draft] = []
    drafts.reserveCapacity(raw.count)
    for (idx, item) in raw.enumerated() {
      let start = offset
      let len = item.text.count
      let end = start + len
      let charProgression = Double(start) / Double(totalChars)
      let sourceProgression = item.locator.locations.progression ?? charProgression
      let boundaryIndex = positionBoundaries.isEmpty ? 0 : findBoundaryIndex(sourceProgression)
      drafts.append(
        Draft(
          idx: idx,
          start: start,
          end: end,
          text: item.text,
          locator: item.locator,
          boundaryIndex: boundaryIndex
        )
      )
      offset = end
    }

    let grouped = Dictionary(grouping: drafts, by: { $0.boundaryIndex })
    let out: [SentenceEntry] = drafts.map { d in
      let pageStart = positionBoundaries.isEmpty ? 0.0 : (positionBoundaries[safe: d.boundaryIndex] ?? 0.0)
      let pageEnd = positionBoundaries.isEmpty ? 1.0 : (positionBoundaries[safe: d.boundaryIndex + 1] ?? 1.0)
      let group = (grouped[d.boundaryIndex] ?? []).sorted { $0.start < $1.start }
      let count = max(group.count, 1)
      let groupIndex = max(group.firstIndex(where: { $0.idx == d.idx }) ?? 0, 0)
      let interval = max(pageEnd - pageStart, 0.0)
      let progression = interval > 0 ? (pageStart + ((Double(groupIndex) + 0.5) / Double(count)) * interval) : pageStart

      return SentenceEntry(
        index: d.idx,
        start: d.start,
        end: d.end,
        text: d.text,
        progression: min(max(progression, 0.0), 1.0),
        pageStartProgression: min(max(pageStart, 0.0), 1.0),
        locator: d.locator
      )
    }

    return out
  }
}
