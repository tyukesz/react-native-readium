/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.reactnativereadium.reader

import android.os.Bundle
import android.view.*
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.reactnativereadium.R
import com.reactnativereadium.utils.LinkOrLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubPreferencesSerializer
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.ContentService
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil

class EpubReaderFragment : VisualReaderFragment() {

    override lateinit var model: ReaderViewModel
    override lateinit var navigator: Navigator
    private lateinit var publication: Publication
    lateinit var navigatorFragment: EpubNavigatorFragment
    private lateinit var factory: ReaderViewModel.Factory
    private lateinit var navigatorFactory: EpubNavigatorFactory
    private val preferencesSerializer = EpubPreferencesSerializer()
    private var initialPreferencesJsonString: String? = null
    private var initialHighlightRangeJsonString: String? = null
    private var initialHighlightSentenceJsonString: String? = null

    private lateinit var userPreferences: EpubPreferences
    private val sentenceIndexCache = SentenceIndexCache()
    private var latestSentenceRequestId: String? = null

    // Accessibility
    private var isExploreByTouchEnabled = false

    private fun ensureUserPreferencesInitialized() {
      if (this::userPreferences.isInitialized) return
      userPreferences = initialPreferencesJsonString?.let {
        preferencesSerializer.deserialize(it)
      } ?: EpubPreferences()
    }

    private fun applyPendingPreferencesIfNeeded() {
      if (!this::navigator.isInitialized) return
      initialPreferencesJsonString?.let { updatePreferencesFromJsonString(it) }
    }

    private fun applyPendingHighlightRangeIfNeeded() {
      if (!this::navigator.isInitialized) return
      initialHighlightRangeJsonString?.let { applyHighlightRangeFromJsonString(it) }
    }

    private fun applyPendingHighlightSentenceIfNeeded() {
      if (!this::navigator.isInitialized) return
      initialHighlightSentenceJsonString?.let { applyHighlightSentenceFromJsonString(it) }
    }

    fun initFactory(
      publication: Publication,
      initialLocation: Locator?
    ) {
      factory = ReaderViewModel.Factory(
        publication,
        initialLocation
      )
      navigatorFactory = EpubNavigatorFactory(publication)
    }

    fun updatePreferencesFromJsonString(serialisedPreferences: String) {
      userPreferences = preferencesSerializer.deserialize(serialisedPreferences)
      sentenceIndexCache.clear()

      if (this::navigator.isInitialized && navigator is EpubNavigatorFragment) {
        (navigator as EpubNavigatorFragment).submitPreferences(userPreferences)
        initialPreferencesJsonString = null
      } else {
        initialPreferencesJsonString = serialisedPreferences
      }
    }

    fun applyHighlightRangeFromJsonString(highlightRangeJson: String?) {
      if (highlightRangeJson.isNullOrBlank()) {
        if (this::navigator.isInitialized) {
          val decorable = navigator as? DecorableNavigator
          if (decorable != null) {
            viewLifecycleOwner.lifecycleScope.launch {
              decorable.applyDecorations(emptyList(), HIGHLIGHT_GROUP)
            }
          }
        }
        initialHighlightRangeJsonString = null
        return
      }

      if (!this::navigator.isInitialized) {
        initialHighlightRangeJsonString = highlightRangeJson
        return
      }

      val decorable = navigator as? DecorableNavigator
      if (decorable == null) {
        initialHighlightRangeJsonString = null
        return
      }

      initialHighlightRangeJsonString = highlightRangeJson

      viewLifecycleOwner.lifecycleScope.launch {
        val decorations = buildDecorationsFromProgressionRange(highlightRangeJson)
        decorable.applyDecorations(decorations, HIGHLIGHT_GROUP)
      }
    }

    fun applyHighlightSentenceFromJsonString(highlightSentenceJson: String?) {
      if (highlightSentenceJson.isNullOrBlank()) {
        if (this::navigator.isInitialized) {
          val decorable = navigator as? DecorableNavigator
          if (decorable != null) {
            viewLifecycleOwner.lifecycleScope.launch {
              decorable.applyDecorations(emptyList(), HIGHLIGHT_GROUP)
            }
          }
        }
        initialHighlightSentenceJsonString = null
        return
      }

      if (!this::navigator.isInitialized) {
        initialHighlightSentenceJsonString = highlightSentenceJson
        return
      }

      val decorable = navigator as? DecorableNavigator
      if (decorable == null) {
        initialHighlightSentenceJsonString = null
        return
      }

      initialHighlightSentenceJsonString = highlightSentenceJson

      viewLifecycleOwner.lifecycleScope.launch {
        val requestId = runCatching {
          JSONObject(highlightSentenceJson).optString("requestId")
        }.getOrNull()
        if (!requestId.isNullOrBlank()) {
          latestSentenceRequestId = requestId
        }

        val result = buildDecorationsFromSentenceIndex(highlightSentenceJson)
        if (requestId.isNullOrBlank() || latestSentenceRequestId == requestId) {
          decorable.applyDecorations(result.decorations, HIGHLIGHT_GROUP)
          result.focusLocator?.let { locator ->
            go(LinkOrLocator.Locator(locator), true)
          }
        }
      }
    }

    private suspend fun buildDecorationsFromProgressionRange(jsonString: String): List<Decoration> {
      val json = runCatching { JSONObject(jsonString) }.getOrNull() ?: return emptyList()
      val href = json.optString("href")
      if (href.isBlank()) return emptyList()

      val highlightStyle = parseHighlightStyle(json)

      val start = json.optDouble("startProgression", Double.NaN)
      val end = json.optDouble("endProgression", Double.NaN)
      if (!start.isFinite() || !end.isFinite()) return emptyList()

      val startP = start.coerceIn(0.0, 1.0)
      val endP = end.coerceIn(0.0, 1.0)
      val rangeStart = minOf(startP, endP)
      val rangeEnd = maxOf(startP, endP)
      if (rangeEnd <= rangeStart) return emptyList()

      // Use a half-open interval [start, end) to avoid overlap between consecutive ranges.
      // The end is inclusive only when reaching the end of the resource.
      val endExclusive = if (rangeEnd >= 1.0) Double.POSITIVE_INFINITY else rangeEnd

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

      // In Readium's HTML navigator, decoration anchoring relies on the Locator model:
      // - if `locator.text.highlight` is present, it uses a TextQuoteAnchor with prefix/suffix
      // - otherwise it falls back to `locations.cssSelector` or `locations.fragments` (HTML IDs)
      // Therefore we must preserve the locators coming from the ContentService instead of
      // generating a synthetic one from progression or truncating the highlight text.
      return withContext(Dispatchers.IO) {
        val content = contentService.content(hrefStartLocator)
        val iterator = content.iterator()
        fun normalizeHref(value: String): String = value.trim().removePrefix("/")
        val targetHref = normalizeHref(href)

        val segments = mutableListOf<SegmentInfo>()
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

          for (segment in textEl.segments) {
            val text = segment.text
            if (text.isBlank()) continue
            segments.add(
              SegmentInfo(
                locator = segment.locator,
                text = text,
                progression = segment.locator.locations.progression
              )
            )
          }
        }

        val totalChars = segments.sumOf { it.text.length.toLong() }
        if (totalChars <= 0L) return@withContext emptyList<Decoration>()

        // Map progressions to character offsets in the concatenated text.
        val startChar = (rangeStart * totalChars.toDouble()).toLong().coerceIn(0L, totalChars)
        val rawEndChar = if (rangeEnd >= 1.0) totalChars else ceil(rangeEnd * totalChars.toDouble()).toLong()
        var endChar = rawEndChar.coerceIn(0L, totalChars)

        // Half-open [startChar, endChar) with a minimum size of 1 char.
        if (endChar <= startChar) {
          endChar = (startChar + 1L).coerceAtMost(totalChars)
        }

        val maxDecorations = 5000
        val decorations = mutableListOf<Decoration>()
        var index = 0
        var offset = 0L

        for (segment in segments) {
          val segLen = segment.text.length.toLong()
          val segStart = offset
          val segEnd = offset + segLen
          offset = segEnd

          if (segEnd <= startChar) continue
          if (segStart >= endChar) break

          val overlapStart = maxOf(startChar, segStart)
          val overlapEnd = minOf(endChar, segEnd)
          if (overlapEnd <= overlapStart) continue

          val localStart = (overlapStart - segStart).toInt()
          val localEnd = (overlapEnd - segStart).toInt()

          val locator = if (localStart == 0 && localEnd == segment.text.length) {
            segment.locator
          } else {
            val prefixStart = (localStart - TEXT_QUOTE_CONTEXT_CHARS).coerceAtLeast(0)
            val suffixEnd = (localEnd + TEXT_QUOTE_CONTEXT_CHARS).coerceAtMost(segment.text.length)
            val before = segment.text.substring(prefixStart, localStart).takeUnless { it.isBlank() }
            val highlight = segment.text.substring(localStart, localEnd)
            val after = segment.text.substring(localEnd, suffixEnd).takeUnless { it.isBlank() }
            segment.locator.copy(text = Locator.Text(before = before, highlight = highlight, after = after))
          }

          // Extra guard: keep decorations ordered and non-overlapping when progression input is.
          val p = locator.locations.progression
          if (p != null) {
            if (p < rangeStart) continue
            if (p >= endExclusive) break
          }

          val style = Decoration.Style.Highlight(highlightStyle.tint, highlightStyle.isActive)
          decorations.add(Decoration("$HIGHLIGHT_ID_PREFIX$index", locator, style, emptyMap<String, Any>()))
          index++
          if (index >= maxDecorations) break
        }

        decorations
      }
    }

    fun getChapterSentencesAsync(
      href: String,
      onSuccess: (List<String>) -> Unit,
      onError: (Throwable) -> Unit,
    ) {
      if (!this::navigator.isInitialized) {
        onError(IllegalStateException("Reader not ready"))
        return
      }
      viewLifecycleOwner.lifecycleScope.launch {
        try {
          val result = computeChapterSentences(href)
          onSuccess(result)
        } catch (e: Throwable) {
          onError(e)
        }
      }
    }

    data class SegmentInfo(
      val locator: Locator,
      val text: String,
      val progression: Double?,
      val blockBreakBefore: Boolean = false,
    )

    data class SentenceSpan(
      val start: Int,
      val end: Int,
      val text: String,
    )

    data class SegmentOffset(
      val locator: Locator,
      val text: String,
      val start: Int,
      val end: Int,
      val progression: Double?,
    )

    data class SentenceEntry(
      val index: Int,
      val start: Int,
      val end: Int,
      val text: String,
      val progression: Double?,
    )

    data class SentenceIndex(
      val href: String,
      val totalChars: Int,
      val segments: List<SegmentOffset>,
      val sentences: List<SentenceEntry>,
      var lastAccessTime: Long,
    )

    private class SentenceIndexCache {
      private val map = LinkedHashMap<String, SentenceIndex>()
      private val maxEntries = 4

      fun get(href: String): SentenceIndex? = map[href]?.also {
        it.lastAccessTime = System.currentTimeMillis()
      }

      fun put(href: String, index: SentenceIndex) {
        map[href] = index
        if (map.size > maxEntries) {
          // Evict least recently accessed
          val oldest = map.entries.minByOrNull { it.value.lastAccessTime }?.key
          if (oldest != null) map.remove(oldest)
        }
      }

      fun clear() {
        map.clear()
      }
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

    private suspend fun getOrBuildSentenceIndex(href: String): SentenceIndex {
      sentenceIndexCache.get(href)?.let { return it }

      val segments = collectSegmentsForHref(href)
      val combined = buildCombinedText(segments)
      val segmentOffsets = buildSegmentOffsets(segments)
      val tocTitle = findTocTitleForHref(href)
      val spans = splitLeadingTitleIfMerged(combined, splitSentencesWithSpans(combined), tocTitle)

      val totalChars = combined.length
      val sentences = spans.mapIndexed { idx, span ->
        // Use a deterministic, monotonic progression derived from the sentence start offset.
        // Segment locators progressions are often sparse or repeated, which makes mapping unstable.
        val progression = if (totalChars > 0) span.start.toDouble() / totalChars.toDouble() else null
        SentenceEntry(
          index = idx,
          start = span.start,
          end = span.end,
          text = span.text,
          progression = progression
        )
      }

      val index = SentenceIndex(
        href = href,
        totalChars = totalChars,
        segments = segmentOffsets,
        sentences = sentences,
        lastAccessTime = System.currentTimeMillis(),
      )
      sentenceIndexCache.put(href, index)
      return index
    }

    private suspend fun computeChapterSentences(href: String): List<String> {
      val index = getOrBuildSentenceIndex(href)
      return index.sentences.map { it.text }
    }

    private data class SentenceDecorationResult(
      val decorations: List<Decoration>,
      val focusLocator: Locator?,
    )

    private suspend fun buildDecorationsFromSentenceIndex(jsonString: String): SentenceDecorationResult {
      val json = runCatching { JSONObject(jsonString) }.getOrNull()
        ?: return SentenceDecorationResult(emptyList(), null)
      val href = json.optString("href")
      if (href.isBlank()) return SentenceDecorationResult(emptyList(), null)

      val highlightStyle = parseHighlightStyle(json)

      val sentenceIndex = json.optInt("sentenceIndex", Int.MIN_VALUE)
      if (sentenceIndex == Int.MIN_VALUE || sentenceIndex < 0) return SentenceDecorationResult(emptyList(), null)

      val sentenceIndexData = getOrBuildSentenceIndex(href)
      if (sentenceIndex >= sentenceIndexData.sentences.size) return SentenceDecorationResult(emptyList(), null)

      val target = sentenceIndexData.sentences[sentenceIndex]
      val startChar = target.start.toLong()
      val endChar = target.end.toLong()
      if (endChar <= startChar) return SentenceDecorationResult(emptyList(), null)

      // Navigation: use a synthetic Locator with a progression when possible.
      // Readium's decoration locators often rely on TextQuoteAnchor (locator.text.*), which
      // does not reliably change the Locator hash (and therefore BaseReaderFragment.go() may
      // skip navigation thinking we're "already there").
      val focusProgression = run {
        val p = target.progression
          ?: if (sentenceIndexData.totalChars > 0) {
            (target.start.toDouble() / sentenceIndexData.totalChars.toDouble())
          } else {
            null
          }
        p?.coerceIn(0.0, 1.0)
      }

      val focusLocator: Locator? = focusProgression?.let { p ->
        Locator.fromJSON(
          JSONObject().apply {
            put("href", href)
            put("type", "application/xhtml+xml")
            put(
              "locations",
              JSONObject().apply {
                put("progression", p)
              }
            )
          }
        )
      }

      val maxDecorations = 5000
      val decorations = mutableListOf<Decoration>()
      var decorationIndex = 0

      // Map combined string offsets to segment-local offsets. We always insert 1 space between
      // segments in buildCombinedText, so each segment consumes segLen (+1 separator except last).
      for (i in sentenceIndexData.segments.indices) {
        val seg = sentenceIndexData.segments[i]
        val segLen = seg.text.length.toLong()
        val segStart = seg.start.toLong()
        val segEnd = seg.end.toLong()

        if (segEnd <= startChar) continue
        if (segStart >= endChar) break

        val overlapStart = maxOf(startChar, segStart)
        val overlapEnd = minOf(endChar, segEnd)
        if (overlapEnd <= overlapStart) continue

        val localStart = (overlapStart - segStart).toInt().coerceIn(0, seg.text.length)
        val localEnd = (overlapEnd - segStart).toInt().coerceIn(0, seg.text.length)
        if (localEnd <= localStart) continue

        val locator = if (localStart == 0 && localEnd == seg.text.length) {
          seg.locator
        } else {
          val prefixStart = (localStart - TEXT_QUOTE_CONTEXT_CHARS).coerceAtLeast(0)
          val suffixEnd = (localEnd + TEXT_QUOTE_CONTEXT_CHARS).coerceAtMost(seg.text.length)
          val before = seg.text.substring(prefixStart, localStart).takeUnless { it.isBlank() }
          val highlight = seg.text.substring(localStart, localEnd)
          val after = seg.text.substring(localEnd, suffixEnd).takeUnless { it.isBlank() }
          seg.locator.copy(text = Locator.Text(before = before, highlight = highlight, after = after))
        }

        val style = Decoration.Style.Highlight(highlightStyle.tint, highlightStyle.isActive)
        decorations.add(Decoration("$HIGHLIGHT_ID_PREFIX$decorationIndex", locator, style, emptyMap<String, Any>()))
        decorationIndex++
        if (decorationIndex >= maxDecorations) break
      }

      // Fallback: if we couldn't derive a progression-based focus locator, navigate to the first
      // decoration locator.
      val fallbackLocator = decorations.firstOrNull()?.locator
      return SentenceDecorationResult(decorations, focusLocator ?: fallbackLocator)
    }

    fun getChapterSentencePageAsync(
      href: String,
      offset: Int,
      limit: Int,
      onSuccess: (total: Int, items: List<SentenceEntry>) -> Unit,
      onError: (Throwable) -> Unit,
    ) {
      if (!this::navigator.isInitialized) {
        onError(IllegalStateException("Reader not ready"))
        return
      }
      viewLifecycleOwner.lifecycleScope.launch {
        try {
          val index = getOrBuildSentenceIndex(href)
          val total = index.sentences.size
          val safeOffset = offset.coerceIn(0, total)
          val safeLimit = limit.coerceAtLeast(0)
          val items = if (safeLimit == 0) {
            emptyList()
          } else {
            index.sentences.drop(safeOffset).take(safeLimit)
          }
          onSuccess(total, items)
        } catch (e: Throwable) {
          onError(e)
        }
      }
    }

    fun getSentenceIndexFromProgressionAsync(
      href: String,
      progression: Double,
      onSuccess: (Int) -> Unit,
      onError: (Throwable) -> Unit,
    ) {
      if (!this::navigator.isInitialized) {
        onError(IllegalStateException("Reader not ready"))
        return
      }
      viewLifecycleOwner.lifecycleScope.launch {
        try {
          val index = getOrBuildSentenceIndex(href)
          val p = progression.coerceIn(0.0, 1.0)
          // Map to character offset, then locate the sentence containing it.
          val totalChars = index.totalChars
          if (totalChars <= 0) {
            onSuccess(0)
            return@launch
          }
          val targetChar = (p * totalChars.toDouble()).toInt().coerceIn(0, totalChars)

          val sentences = index.sentences
          if (sentences.isEmpty()) {
            onSuccess(0)
            return@launch
          }

          var lo = 0
          var hi = sentences.lastIndex
          var found: SentenceEntry? = null

          while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = sentences[mid]
            when {
              targetChar < s.start -> hi = mid - 1
              targetChar >= s.end -> lo = mid + 1
              else -> {
                found = s
                break
              }
            }
          }

          val resolvedIndex = found?.index
            ?: sentences.getOrNull(hi)?.index
            ?: sentences.first().index
          onSuccess(resolvedIndex)
        } catch (e: Throwable) {
          onError(e)
        }
      }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
      check(::navigatorFactory.isInitialized) { "EpubReaderFragment factory was not initialized" }

        ViewModelProvider(this, factory)
          .get(ReaderViewModel::class.java)
          .let {
            model = it
            publication = it.publication
          }

          ensureUserPreferencesInitialized()

          childFragmentManager.fragmentFactory =
            navigatorFactory.createFragmentFactory(
              initialLocator = model.initialLocation,
              initialPreferences = userPreferences,
            )

        setHasOptionsMenu(true)

        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        val navigatorFragmentTag = getString(R.string.epub_navigator_tag)

        if (savedInstanceState == null) {
            childFragmentManager.commitNow {
                add(R.id.fragment_reader_container, EpubNavigatorFragment::class.java, Bundle(), navigatorFragmentTag)
            }
        }
        navigator = childFragmentManager.findFragmentByTag(navigatorFragmentTag) as Navigator
        navigatorFragment = navigator as EpubNavigatorFragment

        applyPendingPreferencesIfNeeded()
        applyPendingHighlightRangeIfNeeded()
        applyPendingHighlightSentenceIfNeeded()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val activity = requireActivity()

        ensureUserPreferencesInitialized()
        applyPendingPreferencesIfNeeded()

        // If TalkBack or any touch exploration service is activated we force scroll mode (and
        // override user preferences)
        val am = activity.getSystemService(AppCompatActivity.ACCESSIBILITY_SERVICE) as AccessibilityManager
        isExploreByTouchEnabled = am.isTouchExplorationEnabled

        userPreferences = if (isExploreByTouchEnabled) {
            userPreferences.plus(EpubPreferences(scroll = true))
        } else {
            userPreferences.plus(EpubPreferences(scroll = null))
        }
        (navigator as? EpubNavigatorFragment)?.submitPreferences(userPreferences)
    }

    companion object {

      private const val HIGHLIGHT_GROUP = "range"
      private const val HIGHLIGHT_ID_PREFIX = "active-"
      private const val TEXT_QUOTE_CONTEXT_CHARS = 32

      private const val DEFAULT_HIGHLIGHT_TINT = 0x59FFFF00.toInt() // ~35% alpha yellow
      private const val DEFAULT_HIGHLIGHT_IS_ACTIVE = false

      private data class HighlightStyleConfig(
        val tint: Int,
        val isActive: Boolean,
      )

      private fun parseTintValue(value: Any?): Int? {
        return when (value) {
          is String -> {
            var s = value.trim()
            if (s.startsWith("#")) s = s.substring(1)
            if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
            if (s.length != 6 && s.length != 8) return null
            val raw = s.toLongOrNull(16) ?: return null
            val argb = if (s.length == 6) {
              0xFF000000L or raw
            } else {
              raw and 0xFFFFFFFFL
            }
            argb.toInt()
          }

          else -> null
        }
      }

      private fun parseHighlightStyle(json: JSONObject): HighlightStyleConfig {
        val style = json.optJSONObject("style")
        val tint = if (style != null && style.has("tint")) {
          parseTintValue(style.opt("tint")) ?: DEFAULT_HIGHLIGHT_TINT
        } else {
          DEFAULT_HIGHLIGHT_TINT
        }
        val isActive = if (style != null && style.has("isActive")) {
          style.optBoolean("isActive")
        } else {
          DEFAULT_HIGHLIGHT_IS_ACTIVE
        }
        return HighlightStyleConfig(tint = tint, isActive = isActive)
      }

        fun newInstance(): EpubReaderFragment {
            return EpubReaderFragment()
        }
    }
}
