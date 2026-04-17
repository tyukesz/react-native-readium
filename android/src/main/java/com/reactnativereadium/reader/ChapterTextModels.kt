package com.reactnativereadium.reader

import org.readium.r2.shared.publication.Locator

data class SegmentInfo(
  val locator: Locator,
  val text: String,
  val progression: Double?,
  val blockBreakBefore: Boolean = false,
)

data class SegmentOffset(
  val locator: Locator,
  val text: String,
  val start: Int,
  val end: Int,
  val progression: Double?,
)

data class PositionEntry(
  val progression: Double,
  val position: Int,
  val totalProgression: Double,
)

data class ChapterRawText(
  val href: String,
  val combinedText: String,
  val totalChars: Int,
  val segments: List<SegmentOffset>,
  val positionEntries: List<PositionEntry>,
)
