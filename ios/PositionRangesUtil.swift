import Foundation
import ReadiumShared

struct PositionRangesUtil {
  static func getPositionRangesByChapter(
    links: [Link],
    positions: [Locator]
  ) -> [String: (start: Int, end: Int)] {
    let rawRanges = buildRawPositionRanges(from: positions)
    return assignRangesForToc(links: links, rawRanges: rawRanges)
  }

  private static func buildRawPositionRanges(
    from positions: [Locator]
  ) -> [String: (start: Int, end: Int)] {
    var ranges: [String: (start: Int, end: Int)] = [:]

    for (index, locator) in positions.enumerated() {
      guard let href = locator.json["href"] as? String else {
        continue
      }

      let key = normalizeHref(href)
      let numericPosition = locator.locations.position ?? (index + 1)

      if let existing = ranges[key] {
        let start = min(existing.start, numericPosition)
        let end = max(existing.end, numericPosition)
        ranges[key] = (start: start, end: end)
      } else {
        ranges[key] = (start: numericPosition, end: numericPosition)
      }
    }

    return ranges
  }

  private static func assignRangesForToc(
    links: [Link],
    rawRanges: [String: (start: Int, end: Int)]
  ) -> [String: (start: Int, end: Int)] {
    var normalizedRanges = rawRanges
    var rangesByHref: [String: (start: Int, end: Int)] = [:]
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

      let range = (start: start, end: end)
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
