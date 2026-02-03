package com.reactnativereadium.reader

import org.readium.r2.shared.publication.Locator

class PublicationPositionResolver(
  private val positions: List<Locator>,
  private val normalizeHref: (String) -> String,
) {
  private data class Entry(
    val position: Int,
    val progression: Double?,
  )

  fun progressionsForHref(hrefKey: String): List<Double> {
    return positions
      .asSequence()
      .filter { normalizeHref(it.href.toString()) == hrefKey }
      .mapNotNull { it.locations.progression }
      .distinct()
      .sorted()
      .toList()
  }

  fun resolveProgressionFromPosition(hrefKey: String, position: Int?): Double? {
    if (position == null) return null
    val entries = entriesForHref(hrefKey)
    if (entries.isEmpty()) return null

    val idx = entries.indexOfFirst { it.position == position }
    if (idx < 0) return null

    entries[idx].progression?.let { return it }

    // Interpolate between nearest known progressions.
    var prevIdx: Int? = null
    var nextIdx: Int? = null

    for (i in (idx - 1) downTo 0) {
      if (entries[i].progression != null) {
        prevIdx = i
        break
      }
    }
    for (i in (idx + 1) until entries.size) {
      if (entries[i].progression != null) {
        nextIdx = i
        break
      }
    }

    if (prevIdx != null && nextIdx != null && nextIdx!! > prevIdx!!) {
      val p0 = entries[prevIdx!!].progression!!
      val p1 = entries[nextIdx!!].progression!!
      val t = (idx - prevIdx!!).toDouble() / (nextIdx!! - prevIdx!!).toDouble()
      return p0 + (p1 - p0) * t
    }
    if (prevIdx != null) return entries[prevIdx!!].progression
    if (nextIdx != null) return entries[nextIdx!!].progression

    // Last resort: relative index within this href.
    if (entries.size <= 1) return 0.0
    return idx.toDouble() / (entries.size - 1).toDouble()
  }

  fun pageRangeFromPosition(hrefKey: String, position: Int?): Pair<Double, Double>? {
    if (position == null) return null
    val entries = entriesForHref(hrefKey)
    if (entries.isEmpty()) return null

    val idx = entries.indexOfFirst { it.position == position }
    if (idx < 0) return null

    fun fallbackProgression(i: Int): Double {
      if (entries.size <= 1) return 0.0
      return i.toDouble() / (entries.size - 1).toDouble()
    }

    val start = (entries[idx].progression ?: fallbackProgression(idx)).coerceIn(0.0, 1.0)
    val end = if (idx + 1 < entries.size) {
      (entries[idx + 1].progression ?: fallbackProgression(idx + 1)).coerceIn(0.0, 1.0)
    } else {
      1.0
    }

    // Guard against non-monotonic or equal boundaries.
    if (end <= start + 1e-12) {
      val minStep = if (entries.size <= 1) 1e-6 else (1.0 / entries.size.toDouble())
      return start to minOf(1.0, start + minStep)
    }

    return start to end
  }

  fun boundaryIndexForProgression(hrefKey: String, progression: Double): Int {
    val progressions = progressionsForHref(hrefKey)
    if (progressions.isEmpty()) return 0

    val p = progression.coerceIn(0.0, 1.0)
    var idx = 0
    for (i in progressions.indices) {
      if (progressions[i] <= p + 1e-9) {
        idx = i
      } else {
        break
      }
    }
    return idx
  }

  fun snapProgressionToContainingBoundary(hrefKey: String, progression: Double): Double {
    val p = progression.coerceIn(0.0, 1.0)
    val progressions = progressionsForHref(hrefKey)
    if (progressions.isEmpty()) return p

    var chosen = progressions.first()
    for (pos in progressions) {
      if (pos <= p + 1e-9) {
        chosen = pos
      } else {
        break
      }
    }
    return chosen.coerceIn(0.0, 1.0)
  }

  private fun entriesForHref(hrefKey: String): List<Entry> {
    return positions
      .asSequence()
      .filter { normalizeHref(it.href.toString()) == hrefKey }
      .mapNotNull { loc ->
        val pos = loc.locations.position ?: return@mapNotNull null
        Entry(pos, loc.locations.progression)
      }
      .sortedBy { it.position }
      .toList()
  }
}
