package com.reactnativereadium

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.reactnativereadium.reader.EpubReaderFragment
import org.json.JSONObject

class HighlightModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = NAME

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

    val view = activity.findViewById<ReadiumView>(reactTag) ?: return
    val fragment = view.fragment as? EpubReaderFragment

    val json = JSONObject().apply {
      put("href", href)
      put("startProgression", startProgression)
      put("endProgression", endProgression)
      style?.let { putStyleIfAny(this, it) }
      put("requestId", System.currentTimeMillis().toString())
    }

    if (fragment == null) {
      // Reader not ready yet; store for later.
      view.updateHighlightRangeFromJsonString(json.toString())
      return
    }

    fragment.applyHighlightRangeFromJsonString(json.toString())
  }

  @ReactMethod
  fun clearHighlight(reactTag: Int) {
    val activity = reactContext.currentActivity ?: return
    val view = activity.findViewById<ReadiumView>(reactTag) ?: return
    val fragment = view.fragment as? EpubReaderFragment
    if (fragment != null) {
      fragment.applyHighlightRangeFromJsonString(null)
      fragment.applyHighlightSentenceFromJsonString(null)
    } else {
      view.updateHighlightRangeFromJsonString(null)
      view.updateHighlightSentenceFromJsonString(null)
    }
  }

  @ReactMethod
  fun highlightSentence(
    reactTag: Int,
    href: String,
    sentenceIndex: Int
  ) {
    highlightSentenceInternal(reactTag, href, sentenceIndex, null)
  }

  @ReactMethod
  fun highlightSentenceWithStyle(
    reactTag: Int,
    href: String,
    sentenceIndex: Int,
    style: ReadableMap?
  ) {
    highlightSentenceInternal(reactTag, href, sentenceIndex, style)
  }

  private fun highlightSentenceInternal(
    reactTag: Int,
    href: String,
    sentenceIndex: Int,
    style: ReadableMap?
  ) {
    val activity = reactContext.currentActivity ?: return
    if (href.isBlank()) return

    val view = activity.findViewById<ReadiumView>(reactTag) ?: return
    val fragment = view.fragment as? EpubReaderFragment

    val json = JSONObject().apply {
      put("href", href)
      put("sentenceIndex", sentenceIndex)
      style?.let { putStyleIfAny(this, it) }
      put("requestId", System.currentTimeMillis().toString())
    }

    if (fragment == null) {
      // Reader not ready yet; store for later.
      view.updateHighlightSentenceFromJsonString(json.toString())
      return
    }

    fragment.applyHighlightSentenceFromJsonString(json.toString())
  }

  @ReactMethod
  fun getChapterSentences(
    reactTag: Int,
    href: String,
    promise: Promise
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

    val view = activity.findViewById<ReadiumView>(reactTag)
    if (view == null) {
      promise.reject("not_found", "ReadiumView not found for reactTag")
      return
    }

    val fragment = view.fragment as? EpubReaderFragment
    if (fragment == null) {
      promise.reject("not_ready", "Reader is not ready yet")
      return
    }

    fragment.getChapterSentencesAsync(href,
      onSuccess = { sentences ->
        val arr = Arguments.createArray().apply {
          sentences.forEach { pushString(it) }
        }
        promise.resolve(arr)
      },
      onError = { error ->
        promise.reject("sentences_error", error.message, error)
      }
    )
  }

  @ReactMethod
  fun getChapterSentencePage(
    reactTag: Int,
    href: String,
    offset: Int,
    limit: Int,
    promise: Promise
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

    val view = activity.findViewById<ReadiumView>(reactTag)
    if (view == null) {
      promise.reject("not_found", "ReadiumView not found for reactTag")
      return
    }

    val fragment = view.fragment as? EpubReaderFragment
    if (fragment == null) {
      promise.reject("not_ready", "Reader is not ready yet")
      return
    }

    fragment.getChapterSentencePageAsync(
      href,
      offset,
      limit,
      onSuccess = { total, items ->
        val payload = Arguments.createMap().apply {
          putInt("total", total)
          putArray(
            "items",
            Arguments.createArray().apply {
              items.forEach { item ->
                pushMap(
                  Arguments.createMap().apply {
                    putInt("index", item.index)
                    putString("text", item.text)
                    if (item.progression != null) {
                      putDouble("progression", item.progression)
                    }
                  }
                )
              }
            }
          )
        }
        promise.resolve(payload)
      },
      onError = { error ->
        promise.reject("sentences_error", error.message, error)
      }
    )
  }

  @ReactMethod
  fun getSentenceIndexFromProgression(
    reactTag: Int,
    href: String,
    progression: Double,
    promise: Promise
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

    val view = activity.findViewById<ReadiumView>(reactTag)
    if (view == null) {
      promise.reject("not_found", "ReadiumView not found for reactTag")
      return
    }

    val fragment = view.fragment as? EpubReaderFragment
    if (fragment == null) {
      promise.reject("not_ready", "Reader is not ready yet")
      return
    }

    fragment.getSentenceIndexFromProgressionAsync(
      href,
      progression,
      onSuccess = { index ->
        promise.resolve(index)
      },
      onError = { error ->
        promise.reject("sentences_error", error.message, error)
      }
    )
  }

  @ReactMethod
  fun highlightSentenceFromProgression(
    reactTag: Int,
    href: String,
    progression: Double,
    promise: Promise
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

    val view = activity.findViewById<ReadiumView>(reactTag)
    if (view == null) {
      promise.reject("not_found", "ReadiumView not found for reactTag")
      return
    }

    val fragment = view.fragment as? EpubReaderFragment
    if (fragment == null) {
      promise.reject("not_ready", "Reader is not ready yet")
      return
    }

    fragment.getSentenceIndexFromProgressionAsync(
      href,
      progression,
      onSuccess = { index ->
        highlightSentence(reactTag, href, index)
        promise.resolve(index)
      },
      onError = { error ->
        promise.reject("sentences_error", error.message, error)
      }
    )
  }

  @ReactMethod
  fun highlightSentenceFromProgressionWithStyle(
    reactTag: Int,
    href: String,
    progression: Double,
    style: ReadableMap?,
    promise: Promise
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

    val view = activity.findViewById<ReadiumView>(reactTag)
    if (view == null) {
      promise.reject("not_found", "ReadiumView not found for reactTag")
      return
    }

    val fragment = view.fragment as? EpubReaderFragment
    if (fragment == null) {
      promise.reject("not_ready", "Reader is not ready yet")
      return
    }

    fragment.getSentenceIndexFromProgressionAsync(
      href,
      progression,
      onSuccess = { index ->
        highlightSentenceWithStyle(reactTag, href, index, style)
        promise.resolve(index)
      },
      onError = { error ->
        promise.reject("sentences_error", error.message, error)
      }
    )
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

  @ReactMethod
  fun highlightSentenceAsync(
    reactTag: Int,
    href: String,
    sentenceIndex: Int,
    promise: Promise
  ) {
    try {
      highlightSentence(reactTag, href, sentenceIndex)
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("highlight_error", e.message, e)
    }
  }

  companion object {
    const val NAME = "HighlightModule"

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
}
