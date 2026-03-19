package com.reactnativereadium

import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.PixelUtil
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.reactnativereadium.reader.BaseReaderFragment
import com.reactnativereadium.reader.EpubReaderFragment
import com.reactnativereadium.reader.PublicationRestrictionConfiguration
import com.reactnativereadium.reader.ReaderViewModel
import com.reactnativereadium.reader.VisualReaderFragment
import com.reactnativereadium.utils.Dimensions
import com.reactnativereadium.utils.File
import com.reactnativereadium.utils.LinkOrLocator
import com.reactnativereadium.utils.MetadataNormalizer
import com.reactnativereadium.utils.toWritableArray
import com.reactnativereadium.utils.toWritableMap
import org.json.JSONArray
import org.readium.r2.shared.publication.Locator
import java.net.URLDecoder
import java.nio.charset.StandardCharsets


class ReadiumView(
  val reactContext: ThemedReactContext
) : FrameLayout(reactContext) {
  companion object {
    private const val TAG = "ReadiumView"
  }

  var dimensions: Dimensions = Dimensions(0,0)
  var file: File? = null
  var fragment: BaseReaderFragment? = null
  var isViewInitialized: Boolean = false
  var isFragmentAdded: Boolean = false
  var lateInitSerializedUserPreferences: String? = null
  var lateInitHighlightRangeJson: String? = null
  var lateInitHighlightSentenceJson: String? = null
  var lateInitHighlightLocatorJson: String? = null
  var hidePageNumbers: Boolean = false
  var disableTextSelection: Boolean = false
  var paywallHTML: String? = null
  private var pendingLocation: LinkOrLocator? = null
  private var frameCallback: Choreographer.FrameCallback? = null
  private var allowedHrefsSet: Set<String>? = null
  private var lastKnownHref: String? = null
  private var publicationPositions: List<Locator> = emptyList()
  private var restrictedAnchorLocator: Locator? = null
  private var activeRestrictedHref: String? = null
  private var pendingRestrictedTargetHref: String? = null
  

  private val gestureDetector = GestureDetector(
    reactContext,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(e: MotionEvent): Boolean {
        return true
      }

      override fun onSingleTapUp(e: MotionEvent): Boolean {
        dispatchTapEvent(e)
        return true
      }
    }
  )

  fun updateLocation(location: LinkOrLocator) : Boolean {
    val effectiveLocation = mapRequestedLocationToRestrictionAnchor(location)

    // When using react-native-screens (native stack), the previous screen is often detached / not visible.
    // Navigating the Readium navigator while not visible can update internal state without updating the
    // rendered WebView. Queue the latest requested location and apply it once visible again.
    pendingLocation = effectiveLocation

    // Always schedule a retry on the next UI tick (helps during screen transition).
    post { applyPendingLocationIfAny() }

    val frag = fragment ?: return false
    if (!isFragmentReadyForNavigation(frag)) {
      return false
    }

    val ok = frag.go(effectiveLocation, true)
    if (ok) pendingLocation = null
    return ok
  }

  fun updateAllowedHrefsFromJsonString(allowedHrefsJson: String?) {
    allowedHrefsSet = parseAllowedHrefs(allowedHrefsJson)
    recomputeRestrictionAnchorLocator()
    reevaluateRestrictionForCurrentHref()
  }

  fun currentRestrictionConfiguration(): PublicationRestrictionConfiguration? {
    val allowedHrefs = allowedHrefsSet ?: return null
    return PublicationRestrictionConfiguration(
      allowedHrefs = allowedHrefs,
      paywallHTML = paywallHTML
    )
  }

  private fun recomputeRestrictionAnchorLocator() {
    val allowed = allowedHrefsSet
    val positions = publicationPositions

    if (allowed == null || positions.isEmpty()) {
      restrictedAnchorLocator = null
      return
    }

    restrictedAnchorLocator = positions.firstOrNull { locator ->
      !allowed.contains(normalizeHrefForComparison(locator.href.toString()))
    }
  }

  private fun parseAllowedHrefs(allowedHrefsJson: String?): Set<String>? {
    val raw = allowedHrefsJson?.trim() ?: return null
    if (raw.isEmpty()) return null

    return try {
      val parsed = JSONArray(raw)
      val normalized = mutableSetOf<String>()
      for (i in 0 until parsed.length()) {
        val href = parsed.optString(i, "")
        if (href.isNotBlank()) {
          normalized.add(normalizeHrefForComparison(href))
        }
      }
      normalized
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse allowedHrefs: ${e.message}")
      null
    }
  }

  private fun normalizeHrefForComparison(rawHref: String): String {
    val trimmed = rawHref.trim().replace(Regex("^/+"), "")
    val withoutFragment = trimmed.substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')

    return try {
      URLDecoder.decode(withoutQuery, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
      withoutQuery
    }
  }

  private fun isHrefRestricted(normalizedHref: String): Boolean {
    val allowed = allowedHrefsSet ?: return false
    return !allowed.contains(normalizedHref)
  }

  private fun resolveHrefFromLocation(location: LinkOrLocator): String? {
    return when (location) {
      is LinkOrLocator.Link -> location.link.href.toString()
      is LinkOrLocator.Locator -> location.locator.href.toString()
    }
  }

  private fun mapRequestedLocationToRestrictionAnchor(location: LinkOrLocator): LinkOrLocator {
    val href = resolveHrefFromLocation(location) ?: return location
    val normalizedHref = normalizeHrefForComparison(href)

    if (!isHrefRestricted(normalizedHref)) {
      pendingRestrictedTargetHref = null
      return location
    }

    pendingRestrictedTargetHref = normalizedHref
    return restrictedAnchorLocator?.let { LinkOrLocator.Locator(it) } ?: location
  }

  private fun emitRestrictedNavigation(href: String) {
    val payload = Arguments.createMap().apply {
      putString("href", href)
    }
    sendEvent(ReadiumViewManager.ON_RESTRICTED_NAVIGATION, payload)
  }

  private fun activateRestrictedNavigationIfNeeded(href: String) {
    if (activeRestrictedHref == href) {
      return
    }

    activeRestrictedHref = href
    emitRestrictedNavigation(href)
  }

  private fun clearRestrictedNavigationIfNeeded() {
    if (activeRestrictedHref == null) {
      return
    }

    activeRestrictedHref = null
    pendingRestrictedTargetHref = null
    emitRestrictedNavigation("")
  }

  private fun handleLocatorAccess(locator: Locator) {
    val rawHref = locator.href.toString()
    val normalizedHref = normalizeHrefForComparison(rawHref)
    lastKnownHref = normalizedHref

    if (isHrefRestricted(normalizedHref)) {
      activateRestrictedNavigationIfNeeded(pendingRestrictedTargetHref ?: normalizedHref)
    } else {
      clearRestrictedNavigationIfNeeded()
    }
  }

  private fun reevaluateRestrictionForCurrentHref() {
    val href = lastKnownHref ?: run {
      clearRestrictedNavigationIfNeeded()
      return
    }

    if (isHrefRestricted(href)) {
      activateRestrictedNavigationIfNeeded(pendingRestrictedTargetHref ?: href)
    } else {
      clearRestrictedNavigationIfNeeded()
    }
  }

  private fun isFragmentReadyForNavigation(frag: BaseReaderFragment): Boolean {
    // `isVisible/isResumed` are critical here: with native stack + screens, the view can be attached
    // while the fragment is not actually visible yet.
    if (windowVisibility != View.VISIBLE || !isShown) return false
    if (!frag.isAdded) return false
    if (!frag.isResumed) return false
    if (!frag.isVisible) return false
    if (frag.view == null) return false
    return true
  }

  private fun applyPendingLocationIfAny() {
    val loc = pendingLocation ?: return
    val frag = fragment ?: return
    if (!isFragmentReadyForNavigation(frag)) return

    if (frag.go(loc, false)) pendingLocation = null
  }

  fun updatePreferencesFromJsonString(preferences: String?) {
    lateInitSerializedUserPreferences = preferences
    if (preferences == null || fragment == null) {
      return
    }

    (fragment as? EpubReaderFragment)?.updatePreferencesFromJsonString(preferences)
  }

  fun updateHighlightRangeFromJsonString(highlightRange: String?) {
    lateInitHighlightRangeJson = highlightRange
    (fragment as? EpubReaderFragment)?.applyHighlightRangeFromJsonString(highlightRange)
  }

  fun updateHighlightSentenceFromJsonString(highlightSentence: String?) {
    lateInitHighlightSentenceJson = highlightSentence
    (fragment as? EpubReaderFragment)?.applyHighlightSentenceFromJsonString(highlightSentence)
  }

  fun updateHighlightLocatorFromJsonString(highlightLocator: String?) {
    lateInitHighlightLocatorJson = highlightLocator
    (fragment as? EpubReaderFragment)?.applyHighlightLocatorFromJsonString(highlightLocator)
  }

  fun addFragment(frag: BaseReaderFragment) {
    val hadFragment = isFragmentAdded
    fragment = frag
    isFragmentAdded = true
    if (!hadFragment) {
      setupLayout()
    }
    updatePageNumberVisibility(hidePageNumbers)
    updateTextSelectionDisabled(disableTextSelection)
    lateInitSerializedUserPreferences?.let { updatePreferencesFromJsonString(it)}
    lateInitHighlightRangeJson?.let { updateHighlightRangeFromJsonString(it) }
    lateInitHighlightSentenceJson?.let { updateHighlightSentenceFromJsonString(it) }
    lateInitHighlightLocatorJson?.let { updateHighlightLocatorFromJsonString(it) }
    val activity = reactContext.currentActivity as? FragmentActivity
    if (activity == null) {
      Log.w(TAG, "Current activity is not a FragmentActivity; cannot add fragment")
    } else {
      activity.supportFragmentManager
        .beginTransaction()
        .replace(this.id, frag, this.id.toString())
        .commitNow()
    }

    // Apply any pending location after the fragment is actually attached.
    post { applyPendingLocationIfAny() }
    post { reevaluateRestrictionForCurrentHref() }

    // Ensure the fragment's view fills the container
    frag.view?.layoutParams = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.MATCH_PARENT
    )

    // subscribe to reader events
    val dispatch: (String, WritableMap?) -> Unit = { eventName, payload -> sendEvent(eventName, payload) }

    frag.channel.receive(frag) { event ->
      when (event) {
        is ReaderViewModel.Event.LocatorUpdate -> {
          handleLocatorAccess(event.locator)
          if (disableTextSelection) {
            (fragment as? EpubReaderFragment)?.reapplyTextSelectionPolicyIfNeeded()
          }
          val payload = event.locator.toWritableMap()
          dispatch(ReadiumViewManager.ON_LOCATION_CHANGE, payload)
        }
        is ReaderViewModel.Event.PublicationReady -> {
          publicationPositions = event.positions
          recomputeRestrictionAnchorLocator()
          val payload = Arguments.createMap().apply {
            putArray("tableOfContents", event.tableOfContents.toWritableArray())
            putArray("positions", event.positions.map { it.toWritableMap() }.let { list ->
              Arguments.createArray().apply {
                list.forEach { pushMap(it) }
              }
            })
            // Use spec-based normalizer to ensure consistent structure
            putMap("metadata", MetadataNormalizer.normalize(event.metadata))
          }
          dispatch(ReadiumViewManager.ON_PUBLICATION_READY, payload)
        }
      }
    }
  }

  fun updatePageNumberVisibility(hide: Boolean) {
    hidePageNumbers = hide
    (fragment as? VisualReaderFragment)?.setPositionLabelHidden(hide)
  }

  fun updateTextSelectionDisabled(disabled: Boolean) {
    disableTextSelection = disabled
    (fragment as? EpubReaderFragment)?.setTextSelectionDisabled(disabled)
  }

  private fun sendEvent(eventName: String, payload: WritableMap?) {
    val eventDispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, this.id)
    if (eventDispatcher != null) {
      eventDispatcher.dispatchEvent(ReadiumEvent(this.id, eventName, payload))
    } else {
      Log.w(TAG, "EventDispatcher is null for view id ${this.id}")
    }
  }

  // Custom event class for new architecture
  private class ReadiumEvent(
    viewTag: Int,
    private val _eventName: String,
    private val _eventData: WritableMap?
  ) : Event<ReadiumEvent>(viewTag) {
    override fun getEventName(): String = _eventName
    override fun getEventData(): WritableMap? = _eventData
  }

  private fun setupLayout() {
    frameCallback = object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        manuallyLayoutChildren()
        this@ReadiumView.viewTreeObserver.dispatchOnGlobalLayout()
        Choreographer.getInstance().postFrameCallback(this)
      }
    }
    frameCallback?.let { Choreographer.getInstance().postFrameCallback(it) }
  }

  private fun dispatchTapEvent(event: MotionEvent) {
    val x = PixelUtil.toDIPFromPixel(event.x)
    val y = PixelUtil.toDIPFromPixel(event.y)
    val payload = Arguments.createMap().apply {
      putDouble("x", x.toDouble())
      putDouble("y", y.toDouble())
    }

    sendEvent(ReadiumViewManager.ON_TAP, payload)
  }

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    gestureDetector.onTouchEvent(ev)
    return super.dispatchTouchEvent(ev)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    // remove frame callback to avoid leaks/continuous callbacks after view is destroyed
    frameCallback?.let {
      try {
        Choreographer.getInstance().removeFrameCallback(it)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to remove frame callback: ${e.message}")
      }
    }
    frameCallback = null
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    // React Native screens may detach/attach views; restore the layout loop if needed.
    if (frameCallback == null && isFragmentAdded) {
      setupLayout()
    }
    applyPendingLocationIfAny()
    reevaluateRestrictionForCurrentHref()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    if (visibility == View.VISIBLE) {
      applyPendingLocationIfAny()
      reevaluateRestrictionForCurrentHref()
    }
  }

  /**
   * Layout all children properly
   */
  private fun manuallyLayoutChildren() {
    // propWidth and propHeight coming from react-native props
    val width = dimensions.width
    val height = dimensions.height

    // Measure and layout each child within this container
    for (i in 0 until childCount) {
      val child = getChildAt(i)
      child.measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
      )
      // Position child at (0, 0) within this container, filling the container
      child.layout(0, 0, width, height)
    }
  }
}
