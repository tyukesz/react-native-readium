package com.reactnativereadium

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.reactnativereadium.reader.EpubReaderFragment

class TextModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = NAME

  @ReactMethod
  fun getVisibleTextRange(
    reactTag: Int,
    options: ReadableMap?,
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

    val fragment = view.fragment as? EpubReaderFragment
    if (fragment == null) {
      promise.reject("not_ready", "Reader is not ready yet")
      return
    }

    val includeText = options?.hasKey("includeText")?.let { has ->
      if (!has) true else options.getBoolean("includeText")
    } ?: true

    val maxTextLength = options?.hasKey("maxTextLength")?.let { has ->
      if (!has) null else options.getInt("maxTextLength")
    }

    val source = options?.hasKey("source")?.let { has ->
      if (!has) null else options.getString("source")
    }

    fragment.getVisibleTextRangeAsync(
      includeText = includeText,
      maxTextLength = maxTextLength,
      source = source,
      onSuccess = { payload ->
        promise.resolve(payload)
      },
      onError = { error ->
        promise.reject("visible_text_error", error.message, error)
      }
    )
  }

  companion object {
    const val NAME = "TextModule"
  }
}
