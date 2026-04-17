package com.reactnativereadium.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.ContentService

class ChapterTextProvider(
  private val publication: Publication,
  private val positionResolverProvider: () -> PublicationPositionResolver,
) {

  suspend fun getRawText(href: String): ChapterRawText {
    val segments = collectSegmentsForHref(href)
    val combined = buildCombinedText(segments)
    val segmentOffsets = buildSegmentOffsets(segments)
    val positionEntries = positionResolverProvider().positionEntriesForHref(href)

    return ChapterRawText(
      href = href,
      combinedText = combined,
      totalChars = combined.length,
      segments = segmentOffsets,
      positionEntries = positionEntries,
    )
  }

  private suspend fun collectSegmentsForHref(href: String): List<SegmentInfo> {
    val hrefStartLocator = Locator.fromJSON(
      JSONObject().apply {
        put("href", href)
        put("type", "application/xhtml+xml")
        put(
          "locations",
          JSONObject().apply {
            put("progression", 0.0)
          }
        )
      }
    ) ?: return emptyList()

    val contentService = publication.findService(ContentService::class)
      ?: return emptyList()

    fun normalizeHref(value: String): String = value.trim().removePrefix("/")
    val targetHref = normalizeHref(href)

    return withContext(Dispatchers.IO) {
      val content = contentService.content(hrefStartLocator)
      val iterator = content.iterator()
      val segments = mutableListOf<SegmentInfo>()

      var shouldInsertBlockBreak = false

      var seenTargetHref = false
      while (true) {
        val next = iterator.nextOrNull() ?: break
        val textEl = next as? Content.TextElement ?: continue

        val elHref = normalizeHref(textEl.locator.href.toString())
        if (!seenTargetHref) {
          if (elHref != targetHref) continue
          seenTargetHref = true
        } else if (elHref != targetHref) {
          break
        }

        for ((segIndex, segment) in textEl.segments.withIndex()) {
          val text = segment.text.trim()
          if (text.isBlank()) continue
          segments.add(
            SegmentInfo(
              locator = segment.locator,
              text = text,
              progression = segment.locator.locations.progression,
              blockBreakBefore = shouldInsertBlockBreak && segIndex == 0
            )
          )
        }

        shouldInsertBlockBreak = true
      }

      segments
    }
  }

  private fun buildCombinedText(segments: List<SegmentInfo>): String {
    if (segments.isEmpty()) return ""
    val sb = StringBuilder()
    for (i in segments.indices) {
      val seg = segments[i]
      if (i != 0) {
        if (seg.blockBreakBefore) {
          sb.append("\n\n")
        } else {
          sb.append(' ')
        }
      }
      sb.append(seg.text)
    }
    return sb.toString()
  }

  private fun buildSegmentOffsets(segments: List<SegmentInfo>): List<SegmentOffset> {
    val offsets = mutableListOf<SegmentOffset>()
    var cursor = 0
    for (i in segments.indices) {
      val seg = segments[i]
      if (i != 0) {
        cursor += if (seg.blockBreakBefore) 2 else 1
      }
      val start = cursor
      val end = start + seg.text.length
      offsets.add(
        SegmentOffset(
          locator = seg.locator,
          text = seg.text,
          start = start,
          end = end,
          progression = seg.progression
        )
      )
      cursor = end
    }
    return offsets
  }
}
