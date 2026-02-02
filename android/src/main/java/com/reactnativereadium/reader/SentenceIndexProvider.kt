package com.reactnativereadium.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.ContentService
import java.text.BreakIterator
import java.util.Locale

class SentenceIndexProvider(
  private val publication: Publication,
  private val positionResolverProvider: () -> PublicationPositionResolver,
) {
  private val cache = SentenceIndexCache()

  fun clearCache() {
    cache.clear()
  }

  suspend fun getIndex(href: String): SentenceIndex {
    cache.get(href)?.let { return it }

    val segments = collectSegmentsForHref(href)
    val combined = buildCombinedText(segments)
    val segmentOffsets = buildSegmentOffsets(segments)
    val tocTitle = findTocTitleForHref(href)
    val spans = splitLeadingTitleIfMerged(combined, splitSentencesWithSpans(combined), tocTitle)

    val positionBoundaries = positionResolverProvider().progressionsForHref(href)

    val totalChars = combined.length

    data class SentenceDraft(
      val index: Int,
      val start: Int,
      val end: Int,
      val text: String,
      val boundaryIndex: Int,
    )

    val drafts = spans.mapIndexed { idx, span ->
      val charProgression = if (totalChars > 0) {
        (span.start.toDouble() / totalChars.toDouble()).coerceIn(0.0, 1.0)
      } else {
        0.0
      }
      val sourceProgression = findSegmentProgressionForOffset(segmentOffsets, span.start) ?: charProgression
      val boundaryIndex = if (positionBoundaries.isEmpty()) 0 else positionResolverProvider()
        .boundaryIndexForProgression(href, sourceProgression)
      SentenceDraft(
        index = idx,
        start = span.start,
        end = span.end,
        text = span.text,
        boundaryIndex = boundaryIndex,
      )
    }

    val draftsByBoundary = drafts.groupBy { it.boundaryIndex }
    val sentences = drafts.map { d ->
      val pageStart = if (positionBoundaries.isEmpty()) 0.0 else positionBoundaries.getOrNull(d.boundaryIndex) ?: 0.0
      val pageEnd = if (positionBoundaries.isEmpty()) 1.0 else positionBoundaries.getOrNull(d.boundaryIndex + 1) ?: 1.0
      val group = draftsByBoundary[d.boundaryIndex].orEmpty().sortedBy { it.start }
      val groupIndex = group.indexOfFirst { it.index == d.index }.coerceAtLeast(0)
      val count = group.size.coerceAtLeast(1)

      val interval = (pageEnd - pageStart).coerceAtLeast(0.0)
      val progression = if (interval > 0) {
        pageStart + ((groupIndex + 0.5) / count.toDouble()) * interval
      } else {
        pageStart
      }

      SentenceEntry(
        index = d.index,
        start = d.start,
        end = d.end,
        text = d.text,
        progression = progression.coerceIn(0.0, 1.0),
        pageStartProgression = pageStart.coerceIn(0.0, 1.0),
      )
    }

    val index = SentenceIndex(
      href = href,
      combinedText = combined,
      totalChars = totalChars,
      segments = segmentOffsets,
      sentences = sentences,
      lastAccessTime = System.currentTimeMillis(),
    )
    cache.put(href, index)
    return index
  }

  suspend fun computeChapterSentences(href: String): List<String> {
    val index = getIndex(href)
    return index.sentences.map { it.text }
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
          val text = segment.text
          if (text.isBlank()) continue
          segments.add(
            SegmentInfo(
              locator = segment.locator,
              text = text,
              progression = segment.locator.locations.progression,
              // Insert a paragraph break between Content.TextElement blocks.
              // This helps sentence tokenization treat headings as separate sentences.
              blockBreakBefore = shouldInsertBlockBreak && segIndex == 0
            )
          )
        }

        // Next TextElement should start a new block.
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

  private fun findSegmentProgressionForOffset(
    segments: List<SegmentOffset>,
    startOffset: Int,
  ): Double? {
    if (segments.isEmpty()) return null

    var lo = 0
    var hi = segments.lastIndex
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val seg = segments[mid]
      when {
        startOffset < seg.start -> hi = mid - 1
        startOffset >= seg.end -> lo = mid + 1
        else -> return seg.progression
      }
    }
    return null
  }

  private fun findTocTitleForHref(href: String): String? {
    fun normalize(value: String): String = value.trim().removePrefix("/").substringBefore('#')
    val target = normalize(href)

    fun walk(links: List<Link>): String? {
      for (link in links) {
        val linkHref = normalize(link.href.toString())
        if (linkHref == target) {
          val title = link.title?.trim()
          if (!title.isNullOrBlank()) return title
        }
        val child = link.children?.let { walk(it) }
        if (child != null) return child
      }
      return null
    }

    return walk(publication.tableOfContents)
  }

  private fun sentenceLocale(): Locale {
    val tag = publication.metadata.languages.firstOrNull()?.toString()?.trim().orEmpty()
    return if (tag.isNotBlank()) Locale.forLanguageTag(tag) else Locale.getDefault()
  }

  private fun splitLeadingTitleIfMerged(
    combined: String,
    spans: List<SentenceSpan>,
    title: String?
  ): List<SentenceSpan> {
    val t = title?.trim()?.takeIf { it.isNotBlank() } ?: return spans
    if (spans.isEmpty()) {
      return listOf(SentenceSpan(start = 0, end = t.length, text = t))
    }

    val first = spans.first()
    if (first.text == t) return spans
    if (!first.text.startsWith(t)) return spans

    // Split the first sentence span into [title] + [rest] so Android aligns with iOS.
    val titleStart = first.start
    val titleEnd = (titleStart + t.length).coerceAtMost(first.end)

    var restStart = titleEnd
    while (restStart < first.end && combined[restStart].isWhitespace()) restStart++
    var restEnd = first.end
    while (restEnd > restStart && combined[restEnd - 1].isWhitespace()) restEnd--

    val out = mutableListOf<SentenceSpan>()
    out.add(SentenceSpan(start = titleStart, end = titleEnd, text = t))
    if (restEnd > restStart) {
      out.add(
        SentenceSpan(
          start = restStart,
          end = restEnd,
          text = combined.substring(restStart, restEnd)
        )
      )
    }

    for (i in 1 until spans.size) out.add(spans[i])
    return out
  }

  private fun splitSentencesWithSpans(text: String): List<SentenceSpan> {
    if (text.isBlank()) return emptyList()

    val spans = mutableListOf<SentenceSpan>()
    val iterator = BreakIterator.getSentenceInstance(sentenceLocale())
    iterator.setText(text)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
      var s = start
      var e = end
      while (s < e && text[s].isWhitespace()) s++
      while (e > s && text[e - 1].isWhitespace()) e--
      if (e > s) {
        val sentenceText = text.substring(s, e)
        spans.add(SentenceSpan(start = s, end = e, text = sentenceText))
      }
      start = end
      end = iterator.next()
    }
    return spans
  }

}
