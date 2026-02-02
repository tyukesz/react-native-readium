package com.reactnativereadium.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class ViewportTextExtractor {
  data class ViewportTextRange(
    val start: Int,
    val end: Int,
    val totalChars: Int,
    val text: String,
  )

  suspend fun extract(root: View?): ViewportTextRange? {
    val webView = findWebView(root) ?: return null
    val json = evaluate(webView) ?: return null

    val totalChars = json.optInt("totalChars", 0).coerceAtLeast(0)
    val startRaw = json.optInt("start", 0)
    val endRaw = json.optInt("end", 0)
    val start = startRaw.coerceIn(0, totalChars)
    val end = endRaw.coerceIn(start, totalChars)
    val text = json.optString("text", "")

    return ViewportTextRange(
      start = start,
      end = end,
      totalChars = totalChars,
      text = text,
    )
  }

  private suspend fun evaluate(webView: WebView): JSONObject? =
    suspendCancellableCoroutine { cont ->
      webView.evaluateJavascript(VIEWPORT_TEXT_JS) { result ->
        if (cont.isCancelled) return@evaluateJavascript
        val raw = result?.trim()
        if (raw.isNullOrBlank() || raw == "null") {
          cont.resume(null)
          return@evaluateJavascript
        }
        try {
          val decoded = if (raw.startsWith("\"")) {
            (JSONTokener(raw).nextValue() as? String) ?: raw
          } else raw
          cont.resume(JSONObject(decoded))
        } catch (_: Throwable) {
          cont.resume(null)
        }
      }
    }

  private fun findWebView(root: View?): WebView? {
    if (root == null) return null
    if (root is WebView) return root
    if (root is ViewGroup) {
      for (i in 0 until root.childCount) {
        val found = findWebView(root.getChildAt(i))
        if (found != null) return found
      }
    }
    return null
  }

  companion object {
    private val VIEWPORT_TEXT_JS = """
      (function() {
        function caretFromPoint(x, y) {
          if (document.caretRangeFromPoint) {
            return document.caretRangeFromPoint(x, y);
          }
          if (document.caretPositionFromPoint) {
            var pos = document.caretPositionFromPoint(x, y);
            if (!pos) return null;
            var r = document.createRange();
            r.setStart(pos.offsetNode, pos.offset);
            r.collapse(true);
            return r;
          }
          return null;
        }

        function rectIntersects(rect, vw, vh) {
          return rect && rect.right > 0 && rect.bottom > 0 && rect.left < vw && rect.top < vh;
        }

        var vw = window.innerWidth || document.documentElement.clientWidth || 0;
        var vh = window.innerHeight || document.documentElement.clientHeight || 0;

        var fullRange = document.createRange();
        fullRange.selectNodeContents(document.body);
        var fullText = fullRange.toString() || "";

        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
        var visibleParts = [];
        var firstRange = null;
        var lastRange = null;

        function appendRange(r) {
          if (!r) return;
          var txt = r.toString();
          if (!txt) return;
          if (lastRange) {
            if (r.compareBoundaryPoints(Range.START_TO_END, lastRange) < 0) {
              if (r.compareBoundaryPoints(Range.END_TO_END, lastRange) <= 0) {
                return;
              }
              r.setStart(lastRange.endContainer, lastRange.endOffset);
              txt = r.toString();
              if (!txt) return;
            }
          }
          if (!firstRange) firstRange = r.cloneRange();
          lastRange = r.cloneRange();
          visibleParts.push(txt);
        }

        while (walker.nextNode()) {
          var node = walker.currentNode;
          if (!node || !node.nodeValue || !node.nodeValue.trim()) continue;
          var nodeRange = document.createRange();
          nodeRange.selectNodeContents(node);
          var rects = nodeRange.getClientRects();
          if (!rects || rects.length === 0) continue;

          for (var i = 0; i < rects.length; i++) {
            var rect = rects[i];
            if (!rectIntersects(rect, vw, vh)) continue;

            var startCaret = caretFromPoint(Math.max(1, rect.left + 1), Math.max(1, rect.top + 1));
            var endCaret = caretFromPoint(Math.max(1, rect.right - 1), Math.max(1, rect.bottom - 1));
            if (!startCaret || !endCaret) continue;

            var r = document.createRange();
            r.setStart(startCaret.startContainer, startCaret.startOffset);
            r.setEnd(endCaret.startContainer, endCaret.startOffset);

            if (r.collapsed) {
              r.setStart(node, 0);
              r.setEnd(node, node.nodeValue.length);
            }

            appendRange(r);
          }
        }

        if (!firstRange || !lastRange) {
          return JSON.stringify({ start: 0, end: 0, totalChars: fullText.length, text: "" });
        }

        var pre = document.createRange();
        pre.selectNodeContents(document.body);
        pre.setEnd(firstRange.startContainer, firstRange.startOffset);
        var start = pre.toString().length;

        var preEnd = document.createRange();
        preEnd.selectNodeContents(document.body);
        preEnd.setEnd(lastRange.endContainer, lastRange.endOffset);
        var end = preEnd.toString().length;

        if (end < start) { var t = start; start = end; end = t; }
        var text = visibleParts.join("");
        return JSON.stringify({ start: start, end: end, totalChars: fullText.length, text: text });
      })();
    """.trimIndent()
  }
}
