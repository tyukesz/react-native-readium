package com.reactnativereadium.reader

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.reactnativereadium.utils.EventChannel
import com.reactnativereadium.utils.LinkOrLocator
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
    val tableOfContents = model.publication.tableOfContents

    viewScope.launch {
      val positionsResult = runCatching { model.publication.positions() }
      val positions = positionsResult.getOrNull().orEmpty()
      val totalPositions = positionsResult.getOrNull()?.size

      val rawRanges = positions.buildRawPositionRanges()
      val annotatedRanges = assignRangesForToc(tableOfContents, rawRanges)

      channel.send(
        ReaderViewModel.Event.TableOfContentsLoaded(
          tableOfContents,
          totalPositions,
          annotatedRanges
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

  private fun List<Locator>.buildRawPositionRanges(): MutableMap<String, PositionRange> {
    val ranges = mutableMapOf<String, PositionRange>()
    forEachIndexed { index, locator ->
      val key = locator.href.toString().normalizeHref()
      val numericPosition = locator.locations.position ?: (index + 1)
      val existing = ranges[key]
      if (existing == null) {
        ranges[key] = PositionRange(numericPosition, numericPosition)
      } else {
        val start = minOf(existing.start ?: numericPosition, numericPosition)
        val end = maxOf(existing.end ?: numericPosition, numericPosition)
        ranges[key] = PositionRange(start, end)
      }
    }
    return ranges
  }

  private fun assignRangesForToc(
    links: List<Link>,
    rawRanges: MutableMap<String, PositionRange>
  ): Map<String, PositionRange> {
    var lastAssigned = 0

    fun assign(link: Link) {
      val key = link.href.toString().normalizeHref()
      val existing = rawRanges[key]
      val tentativeStart = existing?.start ?: lastAssigned + 1
      val start = when {
        existing?.start != null && existing.start > lastAssigned + 1 -> lastAssigned + 1
        else -> tentativeStart
      }
      val tentativeEnd = existing?.end ?: start
      val end = if (tentativeEnd < start) start else tentativeEnd

      rawRanges[key] = PositionRange(start, end)
      lastAssigned = maxOf(lastAssigned, end)

      link.children.forEach { assign(it) }
    }

    links.forEach { assign(it) }
    return rawRanges
  }

  private fun String.normalizeHref(): String =
    this.substringBefore('#').substringBefore('?')
