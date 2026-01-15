package com.reactnativereadium.utils

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

internal object PositionRangesUtil {
  fun getPositionRangesByChapter(
    links: List<Link>,
    positions: List<Locator>
  ): Map<String, PositionRange> {
    val rawRanges = buildRawPositionRanges(positions)
    return assignRangesForToc(links, rawRanges)
  }

  private fun buildRawPositionRanges(
    positions: List<Locator>
  ): MutableMap<String, PositionRange> {
    val ranges = mutableMapOf<String, PositionRange>()
    positions.forEachIndexed { index, locator ->
      val key = locator.href.toString().normalizeHref()
      val numericPosition = locator.locations.position ?: (index + 1)
      val existing = ranges[key]
      if (existing == null) {
        ranges[key] = PositionRange(numericPosition, numericPosition)
      } else {
        val start = minOf(existing.start, numericPosition)
        val end = maxOf(existing.end, numericPosition)
        ranges[key] = PositionRange(start, end)
      }
    }
    return ranges
  }

  private fun assignRangesForToc(
    links: List<Link>,
    rawRanges: MutableMap<String, PositionRange>
  ): Map<String, PositionRange> {
    val rangesByHref = mutableMapOf<String, PositionRange>()
    var lastAssigned = 0

    fun assign(link: Link) {
      val href = link.href.toString()
      val normalizedKey = href.normalizeHref()
      val existing = rawRanges[normalizedKey]
      val start = existing?.start?.coerceAtLeast(lastAssigned + 1) ?: (lastAssigned + 1)
      val tentativeEnd = existing?.end ?: start
      val end = maxOf(tentativeEnd, start)

      val range = PositionRange(start, end)
      rawRanges[normalizedKey] = range
      rangesByHref[href] = range
      lastAssigned = maxOf(lastAssigned, end)

      link.children.forEach { assign(it) }
    }

    links.forEach { assign(it) }
    return rangesByHref
  }

  private fun String.normalizeHref(): String =
    this.substringBefore('#').substringBefore('?')
}
