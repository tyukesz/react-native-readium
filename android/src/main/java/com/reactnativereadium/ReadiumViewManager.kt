package com.reactnativereadium

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.viewmanagers.ReadiumViewManagerDelegate
import com.facebook.react.viewmanagers.ReadiumViewManagerInterface
import com.reactnativereadium.reader.ReaderService
import com.reactnativereadium.utils.File
import com.reactnativereadium.utils.LinkOrLocator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

class ReadiumViewManager(
  val reactContext: ReactApplicationContext
) : ViewGroupManager<ReadiumView>(), ReadiumViewManagerInterface<ReadiumView> {
  private var svc = ReaderService(reactContext)
  private val delegate = ReadiumViewManagerDelegate<ReadiumView, ReadiumViewManager>(this)

  override fun getName() = "ReadiumView"

  override fun getDelegate(): ViewManagerDelegate<ReadiumView> = delegate

  override fun createViewInstance(reactContext: ThemedReactContext): ReadiumView {
    val view = ReadiumView(reactContext)
    view.pendingBuildTrigger = { buildForViewIfReady(view) }
    return view
  }

  override fun create(view: ReadiumView) {
    view.isViewInitialized = true
    if (view.file != null) {
      buildForViewIfReady(view)
    }
  }

  override fun setFile(view: ReadiumView, value: String?) {
    if (value == null) return
    val json = JSONObject(value)
    val path = json.optString("url", "")
      .replace("^(file:/+)?(/.*)$".toRegex(), "$2")
    val initialLocation = json.optJSONObject("initialLocation")
      ?.let { locationToLinkOrLocator(it) }
    view.file = File(path, initialLocation)
    buildForViewIfReady(view)
  }

  override fun setLocation(view: ReadiumView, value: String?) {
    if (value == null) return
    locationToLinkOrLocator(JSONObject(value))?.let { view.updateLocation(it) }
  }

  override fun setPreferences(view: ReadiumView, value: String?) {
    view.updatePreferencesFromJsonString(value)
  }

  override fun setAllowedHrefs(view: ReadiumView, value: String?) {
    view.updateAllowedHrefsFromJsonString(value)
    buildForViewIfReady(view)
  }

  override fun setPaywallHTML(view: ReadiumView, value: String?) {
    view.paywallHTML = value
    buildForViewIfReady(view)
  }

  override fun setHidePageNumbers(view: ReadiumView, value: Boolean) {
    view.updatePageNumberVisibility(value)
  }

  override fun setEnableTapNavigation(view: ReadiumView, value: Boolean) {
    // iOS only - no-op on Android
  }

  override fun setDisableTextSelection(view: ReadiumView, value: Boolean) {
    view.updateTextSelectionDisabled(value)
  }

  private fun locationToLinkOrLocator(json: JSONObject): LinkOrLocator? {
    val hasLocations = json.has("locations")
    val hasType = json.has("type") && !json.getString("type").isEmpty()
    val hasChildren = json.has("children")
    val hasHashHref = (json.get("href") as String).contains("#")
    val hasTemplated = json.has("templated")

    var linkOrLocator: LinkOrLocator? = null

    if ((!hasType || hasChildren || hasHashHref || hasTemplated) && !hasLocations) {
      val link = Link.fromJSON(json)
      if (link != null) {
        linkOrLocator = LinkOrLocator.Link(link)
      }
    } else {
      val locator = Locator.fromJSON(json)
      if (locator != null) {
        linkOrLocator = LinkOrLocator.Locator(locator)
      }
    }

    return linkOrLocator
  }

  private fun buildForViewIfReady(view: ReadiumView) {
    var file = view.file
    val width = view.dimensions.width
    val height = view.dimensions.height

    if (file != null && view.isViewInitialized && width > 0 && height > 0) {
      runBlocking {
        svc.openPublication(
          file.path,
          file.initialLocation,
          view.currentRestrictionConfiguration()
        ) { fragment ->
          view.addFragment(fragment)
        }
      }
    }
  }

  companion object {
    var ON_LOCATION_CHANGE = "onLocationChange"
    var ON_PUBLICATION_READY = "onPublicationReady"
    var ON_TAP = "onTap"
    var ON_RESTRICTED_NAVIGATION = "onRestrictedNavigation"
  }
}
