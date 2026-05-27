package com.reactnativereadium

import android.util.Log
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.facebook.react.bridge.*
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.annotations.ReactPropGroup
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.reactnativereadium.reader.ReaderService
import com.reactnativereadium.utils.File
import com.reactnativereadium.utils.LinkOrLocator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

class ReadiumViewManager(
  val reactContext: ReactApplicationContext
) : ViewGroupManager<ReadiumView>() {
  private var svc = ReaderService(reactContext)

  override fun getName() = "ReadiumView"

  override fun createViewInstance(reactContext: ThemedReactContext): ReadiumView {
    return ReadiumView(reactContext)
  }

  override fun onDropViewInstance(view: ReadiumView) {
    // React Native is destroying the view. Remove the fragment so its WebView and
    // Publication objects don't leak past view lifetime — orphans starve the next
    // open of tile memory and slow it down.
    val frag = view.fragment
    val activity = view.reactContext.currentActivity as? FragmentActivity
    if (frag != null && activity != null) {
      try {
        activity.supportFragmentManager
          .beginTransaction()
          .remove(frag)
          .commitNowAllowingStateLoss()
      } catch (e: IllegalStateException) {
        Log.w(TAG, "Failed to remove fragment on drop: ${e.message}")
      }
    }
    view.fragment = null
    view.isFragmentAdded = false
    super.onDropViewInstance(view)
  }

  override fun getExportedCustomBubblingEventTypeConstants(): Map<String, Any> {
    return MapBuilder.builder<String, Any>()
      .put(
        ON_LOCATION_CHANGE,
        MapBuilder.of(
          "phasedRegistrationNames",
          MapBuilder.of("bubbled", ON_LOCATION_CHANGE)
        )
      )
      .put(
        ON_PUBLICATION_READY,
        MapBuilder.of(
          "phasedRegistrationNames",
          MapBuilder.of("bubbled", ON_PUBLICATION_READY)
        )
      )
      .put(
        ON_TAP,
        MapBuilder.of(
          "phasedRegistrationNames",
          MapBuilder.of("bubbled", ON_TAP)
        )
      )
      .put(
        ON_RESTRICTED_NAVIGATION,
        MapBuilder.of(
          "phasedRegistrationNames",
          MapBuilder.of("bubbled", ON_RESTRICTED_NAVIGATION)
        )
      )
      .build()
  }

  override fun receiveCommand(view: ReadiumView, commandId: String?, args: ReadableArray?) {
    super.receiveCommand(view, commandId, args)

    when (commandId) {
      "create" -> {
        view.isViewInitialized = true
        if (view.file != null) {
          buildForViewIfReady(view)
        }
      }
      else -> {
        Log.w(TAG, "Unknown command received: $commandId")
      }
    }
  }

  @ReactProp(name = "file")
  fun setFile(view: ReadiumView, file: ReadableMap) {
    val path = (file.getString("url") ?: "")
      .replace("^(file:/+)?(/.*)$".toRegex(), "$2")
    val location = file.getMap("initialLocation")
    var initialLocation: LinkOrLocator? = null

    if (location != null) {
      initialLocation = locationToLinkOrLocator(location)
    }

    view.file = File(path, initialLocation)
    this.buildForViewIfReady(view)
  }

  fun locationToLinkOrLocator(location: ReadableMap): LinkOrLocator? {
    val json = JSONObject(location.toHashMap() as HashMap<*, *>)
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

    return linkOrLocator;
  }

  @ReactProp(name = "location")
  fun setLocation(view: ReadiumView, location: ReadableMap) {
    var linkOrLocator: LinkOrLocator? = locationToLinkOrLocator(location)

    if (linkOrLocator != null) {
      view.updateLocation(linkOrLocator)
    }
  }

  @ReactProp(name = "preferences")
  fun setPreferences(view: ReadiumView, serialisedPreferences: String) {
    view.updatePreferencesFromJsonString(serialisedPreferences)
  }

  @ReactProp(name = "allowedHrefs")
  fun setAllowedHrefs(view: ReadiumView, allowedHrefs: String?) {
    view.updateAllowedHrefsFromJsonString(allowedHrefs)
    buildForViewIfReady(view)
  }

  @ReactProp(name = "paywallHTML")
  fun setPaywallHTML(view: ReadiumView, paywallHTML: String?) {
    view.paywallHTML = paywallHTML
    buildForViewIfReady(view)
  }

  @ReactProp(name = "hidePageNumbers", defaultBoolean = false)
  fun setHidePageNumbers(view: ReadiumView, hidePageNumbers: Boolean) {
    view.updatePageNumberVisibility(hidePageNumbers)
  }

  @ReactProp(name = "enableTapNavigation", defaultBoolean = true)
  fun setEnableTapNavigation(view: ReadiumView, enableTapNavigation: Boolean) {
    // iOS only - no-op on Android
  }

  @ReactProp(name = "disableTextSelection", defaultBoolean = false)
  fun setDisableTextSelection(view: ReadiumView, disableTextSelection: Boolean) {
    view.updateTextSelectionDisabled(disableTextSelection)
  }

  @ReactPropGroup(names = ["width", "height"], customType = "Style")
  fun setStyle(view: ReadiumView?, index: Int, value: Int) {
    if (view != null) {
      if (index == 0) {
        view.dimensions.width = value
      }
      if (index == 1) {
        view.dimensions.height = value
      }
      buildForViewIfReady(view)
    }
  }

  private fun buildForViewIfReady(view: ReadiumView) {
    var file = view.file
    val width = view.dimensions.width
    val height = view.dimensions.height

    if (file == null || !view.isViewInitialized || width <= 0 || height <= 0) {
      return
    }

    if (!view.isAttachedToWindow) {
      // Every prop setter (setFile/setStyle/setAllowedHrefs/setPaywallHTML) calls
      // buildForViewIfReady. Without this guard each setter on a not-yet-attached
      // view registers its own listener — attach then fires N opens stacked on the
      // main thread.
      if (view.isBuildScheduled) {
        return
      }
      view.isBuildScheduled = true
      view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
          view.removeOnAttachStateChangeListener(this)
          view.isBuildScheduled = false
          // Defer to next main-loop message: onViewAttachedToWindow fires
          // synchronously inside the host Fragment's transaction (createView →
          // executeOpsTogether). Running addFragment().commitNow() from here
          // throws "FragmentManager is already executing transactions".
          view.post { buildForViewIfReady(view) }
        }
        override fun onViewDetachedFromWindow(v: View) {
          view.removeOnAttachStateChangeListener(this)
          view.isBuildScheduled = false
        }
      })
      return
    }

    // Idempotency: a fragment is already attached for this view, no rebuild needed.
    if (view.isFragmentAdded) {
      return
    }

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

  companion object {
    private const val TAG = "ReadiumViewManager"
    var ON_LOCATION_CHANGE = "onLocationChange"
    var ON_PUBLICATION_READY = "onPublicationReady"
    var ON_TAP = "onTap"
    var ON_RESTRICTED_NAVIGATION = "onRestrictedNavigation"
  }
}
