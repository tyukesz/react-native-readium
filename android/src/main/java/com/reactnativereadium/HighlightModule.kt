package com.reactnativereadium

import android.os.Looper
import android.util.Log
import android.view.View
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.reactnativereadium.reader.EpubReaderFragment
import com.reactnativereadium.utils.toWritableMap
import org.json.JSONObject

class HighlightModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = NAME

  companion object {
    const val NAME = "HighlightModule"
    private const val TAG = "HighlightModule"

    private fun putStyleIfAny(target: JSONObject, style: ReadableMap) {
      val out = JSONObject()
      if (style.hasKey("tint") && style.getType("tint") == ReadableType.String) {
        val tint = style.getString("tint")?.trim()
        if (!tint.isNullOrEmpty()) {
          out.put("tint", tint)
        }
      }
      if (style.hasKey("isActive") && style.getType("isActive") == ReadableType.Boolean) {
        out.put("isActive", style.getBoolean("isActive"))
      }

      if (out.length() > 0) {
        target.put("style", out)
      }
    }
  }

  private inline fun runOnUiThread(activity: android.app.Activity, crossinline block: () -> Unit) {
    if (Looper.getMainLooper().thread == Thread.currentThread()) {
      block()
    } else {
      activity.runOnUiThread { block() }
    }
  }

  private inline fun withEpubReaderFragment(
    reactTag: Int,
    href: String,
    promise: Promise,
    crossinline block: (fragment: EpubReaderFragment) -> Unit
  ) {
    val activity = reactContext.currentActivity
    if (activity == null) {
      promise.reject("no_activity", "Current activity is null")
      return
    }
    if (href.isBlank()) {
      promise.reject("invalid_args", "href is required")
      return
    }

    runOnUiThread(activity) {
      try {
        val view = activity.findViewById<View>(reactTag) as? ReadiumView
        if (view == null) {
          promise.reject("not_found", "ReadiumView not found for reactTag")
          return@runOnUiThread
        }

        val fragment = view.fragment as? EpubReaderFragment
        if (fragment == null) {
          promise.reject("not_ready", "Reader is not ready yet")
          return@runOnUiThread
        }

        block(fragment)
      } catch (t: Throwable) {
        promise.reject("highlight_error", t.message, t)
      }
    }
  }

  @ReactMethod
  fun highlightRange(
    reactTag: Int,
    href: String,
    startProgression: Double,
    endProgression: Double
  ) {
    highlightRangeInternal(reactTag, href, startProgression, endProgression, null)
  }

  @ReactMethod
  fun highlightRangeWithStyle(
    reactTag: Int,
    href: String,
    startProgression: Double,
    endProgression: Double,
    style: ReadableMap?
  ) {
    highlightRangeInternal(reactTag, href, startProgression, endProgression, style)
  }

  private fun highlightRangeInternal(
    reactTag: Int,
    href: String,
    startProgression: Double,
    endProgression: Double,
    style: ReadableMap?
  ) {
    val activity = reactContext.currentActivity ?: return
    if (href.isBlank()) return

    val jsonString = JSONObject().apply {
      put("href", href)
      put("startProgression", startProgression)
      put("endProgression", endProgression)
      style?.let { putStyleIfAny(this, it) }
      put("requestId", System.currentTimeMillis().toString())
    }.toString()

    runOnUiThread(activity) {
      try {
        val view = activity.findViewById<View>(reactTag) as? ReadiumView ?: return@runOnUiThread
        val fragment = view.fragment as? EpubReaderFragment

        if (fragment == null) {
          // Reader not ready yet; store for later.
          view.updateHighlightRangeFromJsonString(jsonString)
        } else {
          fragment.applyHighlightRangeFromJsonString(jsonString)
        }
      } catch (t: Throwable) {
        Log.w(TAG, "highlightRange failed: ${t.message}", t)
      }
    }
  }

  @ReactMethod
  fun clearHighlight(reactTag: Int) {
    val activity = reactContext.currentActivity ?: return
    runOnUiThread(activity) {
      try {
        val view = activity.findViewById<View>(reactTag) as? ReadiumView ?: return@runOnUiThread
        val fragment = view.fragment as? EpubReaderFragment
        if (fragment != null) {
          fragment.applyHighlightRangeFromJsonString(null)

          fragment.applyHighlightLocatorFromJsonString(null)
        } else {
          view.updateHighlightRangeFromJsonString(null)
          view.updateHighlightLocatorFromJsonString(null)
        }
      } catch (t: Throwable) {
        Log.w(TAG, "clearHighlight failed: ${t.message}", t)
      }
    }
  }

  @ReactMethod
  fun highlightLocator(
    reactTag: Int,
    locator: ReadableMap
  ) {
    highlightLocatorInternal(reactTag, locator, null)
  }

  @ReactMethod
  fun highlightLocatorWithStyle(
    reactTag: Int,
    locator: ReadableMap,
    style: ReadableMap?
  ) {
    highlightLocatorInternal(reactTag, locator, style)
  }

  private fun highlightLocatorInternal(
    reactTag: Int,
    locator: ReadableMap,
    style: ReadableMap?
  ) {
    val activity = reactContext.currentActivity ?: return

    val locatorJson = try {
      JSONObject(locator.toHashMap())
    } catch (t: Throwable) {
      Log.w(TAG, "Invalid locator: ${t.message}", t)
      return
    }

    val jsonString = JSONObject().apply {
      put("locator", locatorJson)
      style?.let { putStyleIfAny(this, it) }
      put("requestId", System.currentTimeMillis().toString())
    }.toString()

    runOnUiThread(activity) {
      try {
        val view = activity.findViewById<View>(reactTag) as? ReadiumView ?: return@runOnUiThread
        val fragment = view.fragment as? EpubReaderFragment

        if (fragment == null) {
          // Reader not ready yet; store for later.
          view.updateHighlightLocatorFromJsonString(jsonString)
        } else {
          fragment.applyHighlightLocatorFromJsonString(jsonString)
        }
      } catch (t: Throwable) {
        Log.w(TAG, "highlightLocator failed: ${t.message}", t)
      }
    }
  }

  @ReactMethod
  fun getChapterRawText(
    reactTag: Int,
    href: String,
    promise: Promise
  ) {
    withEpubReaderFragment(reactTag, href, promise) { fragment ->
      fragment.getChapterRawTextAsync(
        href,
        onSuccess = { rawText ->
          val segmentsArray = Arguments.createArray().apply {
            rawText.segments.forEach { seg ->
              pushMap(Arguments.createMap().apply {
                putString("text", seg.text)
                putInt("start", seg.start)
                putInt("end", seg.end)
                putMap("locator", seg.locator.toWritableMap())
              })
            }
          }
          val positionEntriesArray = Arguments.createArray().apply {
            rawText.positionEntries.forEach { entry ->
              pushMap(Arguments.createMap().apply {
                putDouble("progression", entry.progression)
                putInt("position", entry.position)
                putDouble("totalProgression", entry.totalProgression)
              })
            }
          }
          val payload = Arguments.createMap().apply {
            putString("combinedText", rawText.combinedText)
            putArray("segments", segmentsArray)
            putArray("positionEntries", positionEntriesArray)
          }
          promise.resolve(payload)
        },
        onError = { error ->
          promise.reject("raw_text_error", error.message, error)
        }
      )
    }
  }

  @ReactMethod
  fun highlightRangeAsync(
    reactTag: Int,
    href: String,
    startProgression: Double,
    endProgression: Double,
    promise: Promise
  ) {
    // Convenience promise-based wrapper.
    try {
      highlightRange(reactTag, href, startProgression, endProgression)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("highlight_error", e.message, e)
    }
  }

}
