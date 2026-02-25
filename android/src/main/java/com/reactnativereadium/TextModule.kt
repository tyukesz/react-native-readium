package com.reactnativereadium

import android.os.Looper
import android.view.View
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

  private inline fun runOnUiThread(activity: android.app.Activity, crossinline block: () -> Unit) {
    if (Looper.getMainLooper().thread == Thread.currentThread()) {
      block()
    } else {
      activity.runOnUiThread { block() }
    }
  }

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

    val includeText = options?.hasKey("includeText")?.let { has ->
      if (!has) true else options.getBoolean("includeText")
    } ?: true

    val maxTextLength = options?.hasKey("maxTextLength")?.let { has ->
      if (!has) null else options.getInt("maxTextLength")
    }

    val source = options?.hasKey("source")?.let { has ->
      if (!has) null else options.getString("source")
    }?.trim()?.lowercase()
    val resolvedSource = when (source) {
      null, "" -> "viewport"
      "viewport" -> "viewport"
      "approx" -> "approx"
      else -> "viewport"
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

        fragment.getVisibleTextRangeAsync(
          includeText = includeText,
          maxTextLength = maxTextLength,
          source = resolvedSource,
          onSuccess = { payload ->
            promise.resolve(payload)
          },
          onError = { error ->
            promise.reject("visible_text_error", error.message, error)
          }
        )
      } catch (t: Throwable) {
        promise.reject("visible_text_error", t.message, t)
      }
    }
  }

  companion object {
    const val NAME = "TextModule"
  }
}
