import Foundation
import ReadiumShared
import ReadiumNavigator

struct SegmentData {
  let text: String
  let start: Int
  let end: Int
  let locator: Locator
}

struct ChapterRawText {
  let combinedText: String
  let segments: [SegmentData]
}

final class ChapterTextExtractor {

  func getChapterRawText(
    href: String,
    publication: Publication,
    normalizeHref: (String) -> String,
    normalizedHrefForComparison: (String) -> String
  ) async -> ChapterRawText {
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
      return ChapterRawText(combinedText: "", segments: [])
    }

    guard let iterator = publication.content(from: startLocator)?.iterator() else {
      return ChapterRawText(combinedText: "", segments: [])
    }

    var rawSegments: [(text: String, locator: Locator, blockBreakBefore: Bool)] = []
    var started = false
    var shouldInsertBlockBreak = false

    do {
      while true {
        guard let element = try await iterator.next() else {
          break
        }

        guard let textElement = element as? TextContentElement else {
          continue
        }

        var isFirstSegInElement = true
        for segment in textElement.segments {
          let segmentHref = normalizeHref(segment.locator.href.url.relativeString)
          if segmentHref != href {
            if started {
              return buildResult(rawSegments)
            }
            continue
          }

          started = true
          let t = segment.text.trimmingCharacters(in: .whitespacesAndNewlines)
          if t.isEmpty { continue }

          let blockBreak = shouldInsertBlockBreak && isFirstSegInElement
          rawSegments.append((text: t, locator: segment.locator, blockBreakBefore: blockBreak))
          isFirstSegInElement = false
        }

        shouldInsertBlockBreak = true
      }
    } catch {
      // Fall through to build whatever we have.
    }

    return buildResult(rawSegments)
  }

  private func buildResult(
    _ rawSegments: [(text: String, locator: Locator, blockBreakBefore: Bool)]
  ) -> ChapterRawText {
    if rawSegments.isEmpty {
      return ChapterRawText(combinedText: "", segments: [])
    }

    var combinedText = ""
    var segments: [SegmentData] = []
    segments.reserveCapacity(rawSegments.count)

    for (i, seg) in rawSegments.enumerated() {
      if i != 0 {
        if seg.blockBreakBefore {
          combinedText += "\n\n"
        } else {
          combinedText += " "
        }
      }
      let start = combinedText.count
      combinedText += seg.text
      let end = combinedText.count
      segments.append(SegmentData(
        text: seg.text,
        start: start,
        end: end,
        locator: seg.locator
      ))
    }

    return ChapterRawText(combinedText: combinedText, segments: segments)
  }
}
