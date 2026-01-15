package com.reactnativereadium.reader

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.reactnativereadium.utils.EventChannel
import com.reactnativereadium.utils.LinkOrLocator
import com.reactnativereadium.utils.PositionRangesUtil
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Navigator
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positions

/*
 * Base reader fragment class
 *
 * Provides common menu items and saves last location on stop.
 */
abstract class BaseReaderFragment : Fragment() {
  val channel = EventChannel(
    Channel<ReaderViewModel.Event>(Channel.BUFFERED),
    lifecycleScope
  )

  protected abstract val model: ReaderViewModel
  protected abstract val navigator: Navigator

  override fun onCreate(savedInstanceState: Bundle?) {
    setHasOptionsMenu(true)
    super.onCreate(savedInstanceState)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val viewScope = viewLifecycleOwner.lifecycleScope
    val tableOfContents = runCatching { model.publication.tableOfContents }.getOrNull().orEmpty()

    viewScope.launch {
      val positionsResult = runCatching { model.publication.positions() }
      val positions = positionsResult.getOrNull().orEmpty()
      val totalPositions = positionsResult.getOrNull()?.size

      val positionsRanges = PositionRangesUtil.getPositionRangesByChapter(
        tableOfContents,
        positions
      )

      channel.send(
        ReaderViewModel.Event.TableOfContentsLoaded(
          tableOfContents,
          totalPositions,
          positionsRanges
        )
      )
    }
    navigator.currentLocator
      .onEach { channel.send(ReaderViewModel.Event.LocatorUpdate(it)) }
      .launchIn(viewScope)
  }

  override fun onHiddenChanged(hidden: Boolean) {
    super.onHiddenChanged(hidden)
    setMenuVisibility(!hidden)
    requireActivity().invalidateOptionsMenu()
  }

  fun go(location: LinkOrLocator, animated: Boolean): Boolean {
    var locator: Locator? = null
    when (location) {
      is LinkOrLocator.Link -> {
        locator = model.publication.locatorFromLink(location.link)
      }
      is LinkOrLocator.Locator -> {
        locator = location.locator
      }
    }

    if (locator == null) {
      return false
    }

    // don't attempt to navigate if we're already there
    val currentLocator = navigator.currentLocator.value
    if (locator.hashCode() == currentLocator.hashCode()) {
      return true
    }

    return navigator.go(locator, animated)
  }

}
