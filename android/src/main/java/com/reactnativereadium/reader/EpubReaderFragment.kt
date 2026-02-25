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
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.ContentService
import kotlin.math.ceil

class EpubReaderFragment : VisualReaderFragment() {

    private val viewportTextExtractor = ViewportTextExtractor()

    private val positionResolver: PublicationPositionResolver
      get() = PublicationPositionResolver(publicationPositions, ::normalizedHrefForComparison)

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
    private var initialHighlightLocatorJsonString: String? = null

    private lateinit var userPreferences: EpubPreferences
    private lateinit var sentenceIndexProvider: SentenceIndexProvider
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

    private fun applyPendingHighlightLocatorIfNeeded() {
      if (!this::navigator.isInitialized) return
      initialHighlightLocatorJsonString?.let { applyHighlightLocatorFromJsonString(it) }
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
      if (this::sentenceIndexProvider.isInitialized) {
        sentenceIndexProvider.clearCache()
      }

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
        }
      }
    }

    fun applyHighlightLocatorFromJsonString(highlightLocatorJson: String?) {
      if (highlightLocatorJson.isNullOrBlank()) {
        if (this::navigator.isInitialized) {
          val decorable = navigator as? DecorableNavigator
          if (decorable != null) {
            viewLifecycleOwner.lifecycleScope.launch {
              decorable.applyDecorations(emptyList(), HIGHLIGHT_GROUP)
            }
          }
        }
        initialHighlightLocatorJsonString = null
        return
      }

      if (!this::navigator.isInitialized) {
        initialHighlightLocatorJsonString = highlightLocatorJson
        return
      }

      val decorable = navigator as? DecorableNavigator
      if (decorable == null) {
        initialHighlightLocatorJsonString = null
        return
      }

      initialHighlightLocatorJsonString = highlightLocatorJson

      viewLifecycleOwner.lifecycleScope.launch {
        val decorations = buildDecorationsFromLocator(highlightLocatorJson)
        decorable.applyDecorations(decorations, HIGHLIGHT_GROUP)
      }
    }

    private fun buildDecorationsFromLocator(jsonString: String): List<Decoration> {
      val json = runCatching { JSONObject(jsonString) }.getOrNull() ?: return emptyList()
      val locatorJson = json.optJSONObject("locator") ?: return emptyList()
      val locator = Locator.fromJSON(locatorJson) ?: return emptyList()

      val highlightStyle = HighlightStyleParser.parseHighlightStyle(json)
      val style = Decoration.Style.Highlight(highlightStyle.tint, highlightStyle.isActive)
      return listOf(
        Decoration("${HIGHLIGHT_ID_PREFIX}locator", locator, style, emptyMap<String, Any>())
      )
    }

    private suspend fun buildDecorationsFromProgressionRange(jsonString: String): List<Decoration> {
      val json = runCatching { JSONObject(jsonString) }.getOrNull() ?: return emptyList()
      val href = json.optString("href")
      if (href.isBlank()) return emptyList()

      val highlightStyle = HighlightStyleParser.parseHighlightStyle(json)

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


    private fun normalizedHrefForComparison(value: String): String {
      return value.trim().removePrefix("/").substringBefore('#').substringBefore('?')
    }


    fun getVisibleTextRangeAsync(
      includeText: Boolean,
      maxTextLength: Int?,
      source: String?,
      onSuccess: (com.facebook.react.bridge.WritableMap) -> Unit,
      onError: (Throwable) -> Unit,
    ) {
      if (!this::navigator.isInitialized) {
        onError(IllegalStateException("Reader not ready"))
        return
      }

      viewLifecycleOwner.lifecycleScope.launch {
        try {
          val current = navigator.currentLocator.value
          val currentHref = current.href.toString()
          val hrefKey = normalizedHrefForComparison(currentHref)
          val position = current.locations.position

          // Prefer page boundaries derived from the publication position number.
          // This avoids cases where Readium's current locator progression and position can drift,
          // causing consecutive positions (e.g. 24/25) to collapse into the same progression bucket.
          val rangeFromPosition = positionResolver.pageRangeFromPosition(hrefKey, position)

          val pRaw = current.locations.progression
            ?: positionResolver.resolveProgressionFromPosition(hrefKey, position)
            ?: 0.0
          val p = pRaw.coerceIn(0.0, 1.0)

          val (pageStart, pageEnd) = rangeFromPosition ?: run {
            val boundaries = positionResolver.progressionsForHref(hrefKey)
            val boundaryIndex = if (boundaries.isEmpty()) 0 else positionResolver.boundaryIndexForProgression(hrefKey, p)
            val s = if (boundaries.isEmpty()) 0.0 else boundaries.getOrNull(boundaryIndex) ?: 0.0
            val e = if (boundaries.isEmpty()) 1.0 else boundaries.getOrNull(boundaryIndex + 1) ?: 1.0
            s to e
          }
          val EPS = 1e-9

          if (source == "viewport") {
            val viewportPayload = getViewportTextRangePayload(
              includeText = includeText,
              maxTextLength = maxTextLength,
              hrefKey = hrefKey,
              position = position
            )

            if (viewportPayload != null) {
              onSuccess(viewportPayload)
              return@launch
            }
          }

          val index = sentenceIndexProvider.getIndex(hrefKey)
          val totalChars = index.totalChars

          // Prefer sentence-based range (stable), fallback to segment-based.
          val inPageSentences = index.sentences
            .filter { it.progression != null }
            .filter { (it.progression ?: 0.0) >= pageStart - EPS && (it.progression ?: 0.0) < pageEnd - EPS }
            .sortedBy { it.start }

          val rangeFromSentences = if (inPageSentences.isNotEmpty()) {
            val s = inPageSentences.first().start
            val e = inPageSentences.maxOf { it.end }
            s to e
          } else {
            null
          }

          val rangeFromSegments = run {
            val segs = index.segments
              .filter { it.progression != null }
              .filter { (it.progression ?: 0.0) >= pageStart - EPS && (it.progression ?: 0.0) < pageEnd - EPS }
            if (segs.isEmpty()) null else (segs.minOf { it.start } to segs.maxOf { it.end })
          }

          val (startRaw, endRaw) = when {
            rangeFromSegments != null && rangeFromSentences != null -> {
              val s = kotlin.math.max(rangeFromSegments.first, rangeFromSentences.first)
              val e = kotlin.math.min(rangeFromSegments.second, rangeFromSentences.second)
              if (e > s) s to e else rangeFromSegments
            }
            rangeFromSegments != null -> rangeFromSegments
            rangeFromSentences != null -> rangeFromSentences
            else -> (0 to totalChars)
          }
          val start = startRaw.coerceIn(0, totalChars)
          val end = endRaw.coerceIn(start, totalChars)

          val payload = com.facebook.react.bridge.Arguments.createMap().apply {
            putString("href", hrefKey)
            putInt("start", start)
            putInt("end", end)
            putInt("totalChars", totalChars)
            putString("rangeSource", "approx")
            if (position != null) putInt("position", position)

            if (includeText) {
              val available = end - start
              val limit = maxTextLength?.takeIf { it > 0 } ?: available
              val take = minOf(available, limit)
              val textRaw = if (take <= 0) "" else index.combinedText.substring(start, start + take)
              putString("text", sanitizeVisibleTextForJs(textRaw))
              putBoolean("isTruncated", take < available)
            }
          }

          onSuccess(payload)
        } catch (e: Throwable) {
          onError(e)
        }
      }
    }

    private suspend fun getViewportTextRangePayload(
      includeText: Boolean,
      maxTextLength: Int?,
      hrefKey: String,
      position: Int?,
    ): com.facebook.react.bridge.WritableMap? {
      val root = navigatorFragment.view ?: return null
      val viewport = viewportTextExtractor.extract(root) ?: return null

      val totalChars = viewport.totalChars
      val start = viewport.start
      val end = viewport.end

      val payload = com.facebook.react.bridge.Arguments.createMap().apply {
        putString("href", hrefKey)
        putInt("start", start)
        putInt("end", end)
        putInt("totalChars", totalChars)
        putString("rangeSource", "viewport")
        if (position != null) putInt("position", position)

        if (includeText) {
          val fullText = sanitizeVisibleTextForJs(viewport.text)
          val available = (end - start).coerceAtLeast(0)
          val limit = maxTextLength?.takeIf { it > 0 } ?: available
          val take = minOf(available, limit)
          val text = if (take <= 0 || fullText.isEmpty()) "" else fullText.take(take)
          putString("text", text)
          putBoolean("isTruncated", take < available)
        }
      }

      return payload
    }

    private fun sanitizeVisibleTextForJs(text: String): String {
      if (text.isEmpty()) return text
      // Keep character counts stable while removing disruptive whitespace characters.
      return text
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("\t", " ")
    }

    private suspend fun computeChapterSentences(href: String): List<String> {
      val index = sentenceIndexProvider.getIndex(href)
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

      val highlightStyle = HighlightStyleParser.parseHighlightStyle(json)

      val sentenceIndex = json.optInt("sentenceIndex", Int.MIN_VALUE)
      if (sentenceIndex == Int.MIN_VALUE || sentenceIndex < 0) return SentenceDecorationResult(emptyList(), null)

      val sentenceIndexData = sentenceIndexProvider.getIndex(href)
      if (sentenceIndex >= sentenceIndexData.sentences.size) return SentenceDecorationResult(emptyList(), null)

      val target = sentenceIndexData.sentences[sentenceIndex]
      val startChar = target.start.toLong()
      val endChar = target.end.toLong()
      if (endChar <= startChar) return SentenceDecorationResult(emptyList(), null)

      // Navigation focus: use a synthetic Locator with a progression when possible.
      // We use the sentence's computed progression (which is page-aware), so JS can navigate
      // to the containing page deterministically.
      val focusProgression = target.progression?.coerceIn(0.0, 1.0)

      val focusLocator: Locator? = focusProgression?.let { pVal ->
        Locator.fromJSON(
          JSONObject().apply {
            put("href", href)
            put("type", "application/xhtml+xml")
            put(
              "locations",
              JSONObject().apply {
                put("progression", pVal)
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
      onSuccess: (total: Int, items: List<SentencePageItem>) -> Unit,
      onError: (Throwable) -> Unit,
    ) {
      if (!this::navigator.isInitialized) {
        onError(IllegalStateException("Reader not ready"))
        return
      }
      viewLifecycleOwner.lifecycleScope.launch {
        try {
          val index = sentenceIndexProvider.getIndex(href)
          val total = index.sentences.size
          val safeOffset = offset.coerceIn(0, total)
          val safeLimit = limit.coerceAtLeast(0)
          val items = if (safeLimit == 0) {
            emptyList()
          } else {
            index.sentences
              .drop(safeOffset)
              .take(safeLimit)
              .map { s ->
                SentencePageItem(
                  index = s.index,
                  text = s.text,
                  locator = locatorForSentence(index, s),
                )
              }
          }
          onSuccess(total, items)
        } catch (e: Throwable) {
          onError(e)
        }
      }
    }

    data class SentencePageItem(
      val index: Int,
      val text: String,
      val locator: Locator?,
    )

    private fun locatorForSentence(index: SentenceIndex, sentence: SentenceEntry): Locator? {
      val segments = index.segments
      if (segments.isEmpty()) return null

      val startOffset = sentence.start
      val endOffset = sentence.end

      // Find the segment containing the sentence start.
      var lo = 0
      var hi = segments.lastIndex
      var seg: SegmentOffset? = null
      while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val s = segments[mid]
        when {
          startOffset < s.start -> hi = mid - 1
          startOffset >= s.end -> lo = mid + 1
          else -> {
            seg = s
            break
          }
        }
      }
      val resolved = seg ?: return null

      val localStart = (startOffset - resolved.start).coerceIn(0, resolved.text.length)
      val localEnd = (minOf(endOffset, resolved.end) - resolved.start)
        .coerceIn(localStart, resolved.text.length)

      if (localEnd <= localStart) return resolved.locator

      val prefixStart = (localStart - TEXT_QUOTE_CONTEXT_CHARS).coerceAtLeast(0)
      val suffixEnd = (localEnd + TEXT_QUOTE_CONTEXT_CHARS).coerceAtMost(resolved.text.length)
      val before = resolved.text.substring(prefixStart, localStart).takeUnless { it.isBlank() }
      val highlight = resolved.text.substring(localStart, localEnd)
      val after = resolved.text.substring(localEnd, suffixEnd).takeUnless { it.isBlank() }
      return resolved.locator.copy(
        text = Locator.Text(before = before, highlight = highlight, after = after)
      )
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
          val index = sentenceIndexProvider.getIndex(href)
          val p = progression.coerceIn(0.0, 1.0)
          val sentences = index.sentences
          if (sentences.isEmpty()) {
            onSuccess(0)
            return@launch
          }

          val positions = positionResolver.progressionsForHref(href)
          val boundaryIndex = if (positions.isEmpty()) 0 else positionResolver.boundaryIndexForProgression(href, p)
          val pageStart = if (positions.isEmpty()) 0.0 else positions.getOrNull(boundaryIndex) ?: 0.0
          val pageEnd = if (positions.isEmpty()) 1.0 else positions.getOrNull(boundaryIndex + 1) ?: 1.0
          val EPS = 1e-9

          val inPage = sentences
            .filter { it.pageStartProgression != null }
            .filter { (it.pageStartProgression ?: 0.0) >= pageStart - EPS && (it.pageStartProgression ?: 0.0) <= pageStart + EPS }
            .sortedBy { it.progression }

          if (inPage.isEmpty()) {
            onSuccess(sentences.first().index)
            return@launch
          }

          // Exact page boundary => first sentence on that page.
          if (p <= pageStart + EPS) {
            onSuccess(inPage.first().index)
            return@launch
          }

          // Otherwise, floor within the page.
          var resolved = inPage.first().index
          for (s in inPage) {
            val sp = s.progression ?: continue
            if (sp <= p + EPS && sp < pageEnd + EPS) {
              resolved = s.index
            } else {
              break
            }
          }
          onSuccess(resolved)
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

          sentenceIndexProvider = SentenceIndexProvider(publication, { positionResolver })

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
        applyPendingHighlightLocatorIfNeeded()

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

        fun newInstance(): EpubReaderFragment {
            return EpubReaderFragment()
        }
    }
}
