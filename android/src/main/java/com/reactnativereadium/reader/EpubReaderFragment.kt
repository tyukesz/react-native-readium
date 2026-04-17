/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.reactnativereadium.reader

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.reactnativereadium.R
import com.reactnativereadium.utils.LinkOrLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private var initialHighlightLocatorJsonString: String? = null
    private var isTextSelectionDisabled = false
    private var readerBackgroundColor: Int = Color.WHITE

    private lateinit var userPreferences: EpubPreferences
    private lateinit var chapterTextProvider: ChapterTextProvider

    private fun sanitizeLocatorJsonForTextQuoteAnchoring(locatorJson: JSONObject) {
      val highlight = locatorJson.optJSONObject("text")?.optString("highlight")
      if (highlight.isNullOrBlank()) return

      val locations = locatorJson.optJSONObject("locations") ?: return
      val cssSelector = locations.optString("cssSelector")
      if (cssSelector.isBlank()) return

      // If we have TextQuote, avoid brittle positional selectors which might not match
      // the runtime/paginated DOM. Let Readium anchor by TextQuote only.
      if (cssSelector.contains(":nth-child(") || cssSelector.contains(":nth-of-type(")) {
        locations.remove("cssSelector")
      }
    }

    private fun rewriteLocatorForSentence(
      base: Locator,
      hrefKey: String,
      progression: Double,
      resolver: PublicationPositionResolver,
    ): Locator {
      val p = progression.coerceIn(0.0, 1.0)
      val json = base.toJSON()
      val locations = (json.optJSONObject("locations") ?: JSONObject().also { json.put("locations", it) })

      locations.put("progression", p)
      resolver.resolvePositionFromProgression(hrefKey, p)?.let { locations.put("position", it) }
      resolver.resolveTotalProgressionFromProgression(hrefKey, p)?.let { locations.put("totalProgression", it) }

      sanitizeLocatorJsonForTextQuoteAnchoring(json)
      return Locator.fromJSON(json) ?: base
    }

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


    private fun applyPendingHighlightLocatorIfNeeded() {
      if (!this::navigator.isInitialized) return
      initialHighlightLocatorJsonString?.let { applyHighlightLocatorFromJsonString(it) }
    }

    fun setTextSelectionDisabled(disabled: Boolean) {
      isTextSelectionDisabled = disabled
      applyTextSelectionPolicy()
    }

    fun reapplyTextSelectionPolicyIfNeeded() {
      if (!isTextSelectionDisabled) {
        return
      }

      applyTextSelectionPolicy()
    }

    private fun applyTextSelectionPolicy() {
      if (!this::navigatorFragment.isInitialized) {
        return
      }

      val root = navigatorFragment.view ?: view ?: return

      root.post {
        findWebViews(navigatorFragment.view ?: view).forEach { webView ->
          webView.evaluateJavascript(buildTextSelectionPolicyJs(isTextSelectionDisabled), null)
        }
      }
    }

    private fun findWebViews(root: View?): List<WebView> {
      if (root == null) return emptyList()

      val all = mutableListOf<WebView>()
      fun collect(view: View?) {
        if (view == null) return
        if (view is WebView) {
          all.add(view)
          return
        }
        if (view is ViewGroup) {
          for (i in 0 until view.childCount) {
            collect(view.getChildAt(i))
          }
        }
      }

      collect(root)
      return all
    }

    private fun buildTextSelectionPolicyJs(disabled: Boolean): String {
      val disabledLiteral = if (disabled) "true" else "false"
      return """
        (function() {
          var disabled = $disabledLiteral;
          var styleId = 'readium-disable-text-selection-style';
          var css = 'html, body, body * { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; }';
          var root = document.head || document.documentElement;

          if (!root) {
            return false;
          }

          var style = document.getElementById(styleId);
          if (disabled) {
            if (!style) {
              style = document.createElement('style');
              style.id = styleId;
              root.appendChild(style);
            }
            style.textContent = css;

            var selection = window.getSelection ? window.getSelection() : null;
            if (selection && selection.removeAllRanges) {
              selection.removeAllRanges();
            }
          } else if (style && style.parentNode) {
            style.parentNode.removeChild(style);
          }

          return true;
        })();
      """.trimIndent()
    }

    private fun applyReaderBackgroundColor() {
      val rootView = view ?: return

      rootView.setBackgroundColor(readerBackgroundColor)
      binding.root.setBackgroundColor(readerBackgroundColor)
      binding.fragmentReaderContainer.setBackgroundColor(readerBackgroundColor)
      if (this::navigatorFragment.isInitialized) {
        navigatorFragment.view?.setBackgroundColor(readerBackgroundColor)
      }
    }

    private fun resolveReaderBackgroundColor(serialisedPreferences: String): Int {
      val json = runCatching { JSONObject(serialisedPreferences) }.getOrNull()
        ?: return Color.WHITE
      val rawColor = json.opt("backgroundColor") ?: return Color.WHITE

      if (rawColor is Number) {
        return rawColor.toInt()
      }

      val parsedColor = rawColor.toString().trim()
      if (parsedColor.isEmpty()) {
        return Color.WHITE
      }

      return runCatching { Color.parseColor(parsedColor) }
        .getOrElse { Color.WHITE }
    }

    private fun adaptPreferencesJsonForAndroidSerializer(serialisedPreferences: String): String {
      val json = runCatching { JSONObject(serialisedPreferences) }.getOrNull()
        ?: return serialisedPreferences

      listOf("backgroundColor", "textColor").forEach { key ->
        val rawValue = json.opt(key) ?: return@forEach
        if (rawValue is Number) {
          return@forEach
        }

        val parsedColor = rawValue.toString().trim()
        if (parsedColor.isEmpty()) {
          json.remove(key)
          return@forEach
        }

        runCatching { Color.parseColor(parsedColor) }
          .onSuccess { json.put(key, it) }
      }

      return json.toString()
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
      val adaptedPreferences = adaptPreferencesJsonForAndroidSerializer(serialisedPreferences)

      userPreferences = preferencesSerializer.deserialize(adaptedPreferences)
      readerBackgroundColor = resolveReaderBackgroundColor(serialisedPreferences)
      // Sentence cache is now on JS side; no native cache to clear.

      applyReaderBackgroundColor()

      if (this::navigator.isInitialized && navigator is EpubNavigatorFragment) {
        (navigator as EpubNavigatorFragment).submitPreferences(userPreferences)
        initialPreferencesJsonString = null
      } else {
        initialPreferencesJsonString = adaptedPreferences
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

        // Best-effort retry: navigateTo() resolution doesn't guarantee the paginated DOM is ready.
        // Re-applying after a short delay helps make highlights visible on slow renders.
        delay(150)
        if (initialHighlightLocatorJsonString == highlightLocatorJson) {
          decorable.applyDecorations(decorations, HIGHLIGHT_GROUP)
        }
      }
    }

    private fun buildDecorationsFromLocator(jsonString: String): List<Decoration> {
      val json = runCatching { JSONObject(jsonString) }.getOrNull() ?: return emptyList()
      val locatorJson = json.optJSONObject("locator") ?: return emptyList()

      sanitizeLocatorJsonForTextQuoteAnchoring(locatorJson)
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

      val hrefKey = normalizedHrefForComparison(href)

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
          put("href", hrefKey)
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
        val targetHref = normalizeHref(hrefKey)

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

          val locatorBase = if (localStart == 0 && localEnd == segment.text.length) {
            segment.locator
          } else {
            val prefixStart = (localStart - TEXT_QUOTE_CONTEXT_CHARS).coerceAtLeast(0)
            val suffixEnd = (localEnd + TEXT_QUOTE_CONTEXT_CHARS).coerceAtMost(segment.text.length)
            val before = segment.text.substring(prefixStart, localStart).takeUnless { it.isBlank() }
            val highlight = segment.text.substring(localStart, localEnd)
            val after = segment.text.substring(localEnd, suffixEnd).takeUnless { it.isBlank() }
            segment.locator.copy(text = Locator.Text(before = before, highlight = highlight, after = after))
          }

          val locator = locatorBase.let {
            val locJson = it.toJSON()
            sanitizeLocatorJsonForTextQuoteAnchoring(locJson)
            Locator.fromJSON(locJson) ?: it
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

    fun getChapterRawTextAsync(
      href: String,
      onSuccess: (ChapterRawText) -> Unit,
      onError: (Throwable) -> Unit,
    ) {
      if (!this::navigator.isInitialized) {
        onError(IllegalStateException("Reader not ready"))
        return
      }
      viewLifecycleOwner.lifecycleScope.launch {
        try {
          val hrefKey = normalizedHrefForComparison(href)
          val rawText = chapterTextProvider.getRawText(hrefKey)
          onSuccess(rawText)
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

          val rawText = chapterTextProvider.getRawText(hrefKey)
          val totalChars = rawText.totalChars

          // Use segment-based range (sentences are now on JS side).
          val rangeFromSegments = run {
            val segs = rawText.segments
              .filter { it.progression != null }
              .filter { (it.progression ?: 0.0) >= pageStart - EPS && (it.progression ?: 0.0) < pageEnd - EPS }
            if (segs.isEmpty()) null else (segs.minOf { it.start } to segs.maxOf { it.end })
          }

          val (startRaw, endRaw) = rangeFromSegments ?: (0 to totalChars)
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
              val textRaw = if (take <= 0) "" else rawText.combinedText.substring(start, start + take)
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


    override fun onCreate(savedInstanceState: Bundle?) {
      check(::navigatorFactory.isInitialized) { "EpubReaderFragment factory was not initialized" }

        ViewModelProvider(this, factory)
          .get(ReaderViewModel::class.java)
          .let {
            model = it
            publication = it.publication
          }

          chapterTextProvider = ChapterTextProvider(publication, { positionResolver })

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

        applyPendingHighlightLocatorIfNeeded()
        reapplyTextSelectionPolicyIfNeeded()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
      applyReaderBackgroundColor()
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
        applyReaderBackgroundColor()
        reapplyTextSelectionPolicyIfNeeded()
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
