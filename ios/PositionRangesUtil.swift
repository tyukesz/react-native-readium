import Foundation
import ReadiumShared

struct PositionRange {
  var start: Int
  var end: Int
}

struct PositionRangesUtil {
  static func getPositionRangesByChapter(
    links: [Link],
    positions: [Locator]
  ) -> [String: PositionRange] {
    let rawRanges = buildRawPositionRanges(from: positions)
    return assignRangesForToc(links: links, rawRanges: rawRanges)
  }

  private static func buildRawPositionRanges(
    from positions: [Locator]
  ) -> [String: PositionRange] {
    var ranges: [String: PositionRange] = [:]

    for (index, locator) in positions.enumerated() {
      guard let href = locator.json["href"] as? String else {
        continue
      }

      let key = normalizeHref(href)
      let numericPosition = locator.locations.position ?? (index + 1)

      if let existing = ranges[key] {
        let start = min(existing.start, numericPosition)
        let end = max(existing.end, numericPosition)
        ranges[key] = PositionRange(start: start, end: end)
      } else {
        ranges[key] = PositionRange(start: numericPosition, end: numericPosition)
      }
    }

    return ranges
  }

  private static func assignRangesForToc(
    links: [Link],
    rawRanges: [String: PositionRange]
  ) -> [String: PositionRange] {
    var normalizedRanges = rawRanges
    var rangesByHref: [String: PositionRange] = [:]
    var lastAssigned = 0

    func assign(_ link: Link) {
      guard let href = link.json["href"] as? String else {
        link.children.forEach(assign)
        return
      }

      let normalizedKey = normalizeHref(href)
      let existing = normalizedRanges[normalizedKey]
      let start: Int
      if let existingStart = existing?.start {
        start = Swift.max(existingStart, lastAssigned + 1)
      } else {
        start = lastAssigned + 1
      }
      let endCandidate = existing?.end ?? start
      let end = Swift.max(endCandidate, start)

      let range = PositionRange(start: start, end: end)
      normalizedRanges[normalizedKey] = range
      rangesByHref[href] = range
      lastAssigned = max(lastAssigned, end)

      link.children.forEach(assign)
    }

    links.forEach(assign)
    return rangesByHref
  }

  private static func normalizeHref(_ href: String) -> String {
    let noFragment = href.components(separatedBy: "#").first ?? href
    return noFragment.components(separatedBy: "?").first ?? noFragment
  }
}
