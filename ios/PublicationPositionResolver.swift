import Foundation

struct PublicationPositionResolver {
  static func boundaryIndex(for progression: Double, in boundaries: [Double], eps: Double = 1e-9) -> Int {
    guard !boundaries.isEmpty else { return 0 }
    let p = min(max(progression, 0.0), 1.0)
    var idx = 0
    for i in boundaries.indices {
      if boundaries[i] <= p + eps {
        idx = i
      } else {
        break
      }
    }
    return idx
  }

  static func pageRangeFromPosition(
    position: Int?,
    entries: [(position: Int, progression: Double?)],
    eps: Double = 1e-12
  ) -> (Double, Double)? {
    guard let pos = position, !entries.isEmpty else { return nil }
    guard let idx = entries.firstIndex(where: { $0.position == pos }) else { return nil }

    func fallbackProgression(_ i: Int) -> Double {
      if entries.count <= 1 { return 0.0 }
      return Double(i) / Double(entries.count - 1)
    }

    let start = min(max(entries[idx].progression ?? fallbackProgression(idx), 0.0), 1.0)
    let end: Double = {
      let nextIdx = idx + 1
      if nextIdx < entries.count {
        return min(max(entries[nextIdx].progression ?? fallbackProgression(nextIdx), 0.0), 1.0)
      }
      return 1.0
    }()

    if end <= start + eps {
      let minStep = entries.count <= 1 ? 1e-6 : (1.0 / Double(entries.count))
      return (start, min(1.0, start + minStep))
    }

    return (start, end)
  }
}
