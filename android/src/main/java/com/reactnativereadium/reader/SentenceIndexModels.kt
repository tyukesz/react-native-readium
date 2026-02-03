package com.reactnativereadium.reader

import org.readium.r2.shared.publication.Locator

data class SegmentInfo(
  val locator: Locator,
  val text: String,
  val progression: Double?,
  val blockBreakBefore: Boolean = false,
)

data class SentenceSpan(
  val start: Int,
  val end: Int,
  val text: String,
)

data class SegmentOffset(
  val locator: Locator,
  val text: String,
  val start: Int,
  val end: Int,
  val progression: Double?,
)

data class SentenceEntry(
  val index: Int,
  val start: Int,
  val end: Int,
  val text: String,
  val progression: Double?,
  val pageStartProgression: Double?,
)

data class SentenceIndex(
  val href: String,
  val combinedText: String,
  val totalChars: Int,
  val segments: List<SegmentOffset>,
  val sentences: List<SentenceEntry>,
  var lastAccessTime: Long,
)

class SentenceIndexCache {
  private val map = LinkedHashMap<String, SentenceIndex>()
  private val maxEntries = 4

  fun get(href: String): SentenceIndex? = map[href]?.also {
    it.lastAccessTime = System.currentTimeMillis()
  }

  fun put(href: String, index: SentenceIndex) {
    map[href] = index
    if (map.size > maxEntries) {
      // Evict least recently accessed
      val oldest = map.entries.minByOrNull { it.value.lastAccessTime }?.key
      if (oldest != null) map.remove(oldest)
    }
  }

  fun clear() {
    map.clear()
  }
}
