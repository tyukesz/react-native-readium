package com.reactnativereadium

import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
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
import com.reactnativereadium.reader.ReaderViewModel
import com.reactnativereadium.reader.VisualReaderFragment
import com.reactnativereadium.utils.Dimensions
import com.reactnativereadium.utils.File
import com.reactnativereadium.utils.LinkOrLocator
import com.reactnativereadium.utils.MetadataNormalizer
import com.reactnativereadium.utils.toWritableArray
import com.reactnativereadium.utils.toWritableMap


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
  private var frameCallback: Choreographer.FrameCallback? = null
  

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
    return fragment?.go(location, true) ?: false
  }

  fun updatePreferencesFromJsonString(preferences: String?) {
    lateInitSerializedUserPreferences = preferences
    if (preferences == null || fragment == null) {
      return
    }

    (fragment as? EpubReaderFragment)?.updatePreferencesFromJsonString(preferences)
  }

  fun addFragment(frag: BaseReaderFragment) {
    if (isFragmentAdded) {
      return
    }

    fragment = frag
    isFragmentAdded = true
    setupLayout()
    lateInitSerializedUserPreferences?.let { updatePreferencesFromJsonString(it)}
    val activity = reactContext.currentActivity as? FragmentActivity
    if (activity == null) {
      Log.w(TAG, "Current activity is not a FragmentActivity; cannot add fragment")
    } else {
      activity.supportFragmentManager
        .beginTransaction()
        .replace(this.id, frag, this.id.toString())
        .commitNow()
    }

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
          val payload = event.locator.toWritableMap()
          dispatch(ReadiumViewManager.ON_LOCATION_CHANGE, payload)
        }
        is ReaderViewModel.Event.PublicationReady -> {
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

