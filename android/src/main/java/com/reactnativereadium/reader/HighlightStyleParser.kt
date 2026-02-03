package com.reactnativereadium.reader

import org.json.JSONObject

object HighlightStyleParser {
  data class HighlightStyleConfig(
    val tint: Int,
    val isActive: Boolean,
  )

  private const val DEFAULT_HIGHLIGHT_TINT = 0x59FFFF00.toInt() // ~35% alpha yellow
  private const val DEFAULT_HIGHLIGHT_IS_ACTIVE = false

  fun parseHighlightStyle(json: JSONObject): HighlightStyleConfig {
    val style = json.optJSONObject("style")
    val tint = if (style != null && style.has("tint")) {
      parseTintValue(style.opt("tint")) ?: DEFAULT_HIGHLIGHT_TINT
    } else {
      DEFAULT_HIGHLIGHT_TINT
    }
    val isActive = if (style != null && style.has("isActive")) {
      style.optBoolean("isActive")
    } else {
      DEFAULT_HIGHLIGHT_IS_ACTIVE
    }
    return HighlightStyleConfig(tint = tint, isActive = isActive)
  }

  private fun parseTintValue(value: Any?): Int? {
    return when (value) {
      is String -> {
        var s = value.trim()
        if (s.startsWith("#")) s = s.substring(1)
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
        if (s.length != 6 && s.length != 8) return null
        val raw = s.toLongOrNull(16) ?: return null
        val argb = if (s.length == 6) {
          0xFF000000L or raw
        } else {
          raw and 0xFFFFFFFFL
        }
        argb.toInt()
      }

      else -> null
    }
  }
}
