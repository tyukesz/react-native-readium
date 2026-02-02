package com.reactnativereadium

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.reactnativereadium.utils.LinkOrLocator
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

class NavigationModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = NAME

  @ReactMethod
  fun navigateTo(
    reactTag: Int,
    location: ReadableMap,
    promise: Promise
  ) {
    val activity = reactContext.currentActivity
    if (activity == null) {
      promise.reject("no_activity", "Current activity is null")
      return
    }

    val view = activity.findViewById<ReadiumView>(reactTag)
    if (view == null) {
      promise.reject("not_found", "ReadiumView not found for reactTag")
      return
    }

    val linkOrLocator = locationToLinkOrLocator(location)
    if (linkOrLocator == null) {
      promise.reject("invalid_location", "Invalid location")
      return
    }

    // Returns true if navigation executed immediately, false if queued (e.g. screen transition).
    val ok = view.updateLocation(linkOrLocator)
    promise.resolve(ok)
  }

  private fun locationToLinkOrLocator(location: ReadableMap): LinkOrLocator? {
    val json = JSONObject(location.toHashMap() as HashMap<*, *>)
    val hasLocations = json.has("locations")
    val hasType = json.has("type") && !json.optString("type").isNullOrEmpty()
    val hasChildren = json.has("children")
    val hasHashHref = (json.optString("href")).contains("#")
    val hasTemplated = json.has("templated")

    return if ((!hasType || hasChildren || hasHashHref || hasTemplated) && !hasLocations) {
      val link = Link.fromJSON(json) ?: return null
      LinkOrLocator.Link(link)
    } else {
      val locator = Locator.fromJSON(json) ?: return null
      LinkOrLocator.Locator(locator)
    }
  }

  companion object {
    const val NAME = "NavigationModule"
  }
}
