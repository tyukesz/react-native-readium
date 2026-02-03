import Foundation
import UIKit
import WebKit

final class ViewportTextExtractor {
  struct ViewportTextRange {
    let start: Int
    let end: Int
    let totalChars: Int
    let text: String
  }

  func extract(from root: UIView?) async -> ViewportTextRange? {
    guard let webView = findWKWebView(in: root) else { return nil }

    // Ensure the webview is attached and finished loading before running JS.
    let ready = await waitForWebViewReady(webView)
    if !ready {
      print("ViewportTextExtractor: webView not ready for JS evaluation")
      return nil
    }

    return await withCheckedContinuation { continuation in
      webView.evaluateJavaScript(Self.viewportTextJS) { result, error in
        if let error = error {
          print("ViewportTextExtractor: evaluateJavaScript error:\n", error.localizedDescription)
          continuation.resume(returning: nil)
          return
        }

        guard let jsonString = result as? String else {
          print("ViewportTextExtractor: unexpected JS result type: \(type(of: result))")
          continuation.resume(returning: nil)
          return
        }

        guard let data = jsonString.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data, options: []),
              let dict = obj as? [String: Any] else {
          print("ViewportTextExtractor: failed to parse JS JSON result")
          continuation.resume(returning: nil)
          return
        }

        if let e = dict["__error"] as? String {
          print("ViewportTextExtractor: JS error: \(e)")
          continuation.resume(returning: nil)
          return
        }

        let totalChars = (dict["totalChars"] as? NSNumber)?.intValue ?? 0
        let startRaw = (dict["start"] as? NSNumber)?.intValue ?? 0
        let endRaw = (dict["end"] as? NSNumber)?.intValue ?? 0
        let cappedTotal = max(totalChars, 0)
        let start = min(max(startRaw, 0), cappedTotal)
        let end = min(max(endRaw, start), max(cappedTotal, start))
        let text = (dict["text"] as? String) ?? ""

        continuation.resume(returning: ViewportTextRange(
          start: start,
          end: end,
          totalChars: totalChars,
          text: text
        ))
      }
    }
  }

  private func waitForWebViewReady(_ webView: WKWebView) async -> Bool {
    // Wait until the webView is attached to a window and not loading, with a short timeout.
    let maxAttempts = 10
    let delayMs: UInt64 = 100 * 1_000_000 // 100ms
    for _ in 0..<maxAttempts {
      if webView.window != nil && !webView.isLoading && webView.bounds.width > 0 && webView.bounds.height > 0 {
        return true
      }
      try? await Task.sleep(nanoseconds: delayMs)
    }
    return false
  }

  private func findWKWebView(in view: UIView?) -> WKWebView? {
    guard let view = view else { return nil }

    var all: [WKWebView] = []
    func collect(_ v: UIView?) {
      guard let v else { return }
      if let wv = v as? WKWebView {
        all.append(wv)
        return
      }
      for s in v.subviews { collect(s) }
    }
    collect(view)
    if all.isEmpty { return nil }
    if all.count == 1 { return all[0] }

      func visibleArea(_ v: UIView) -> CGFloat {
        guard !v.isHidden, v.alpha > 0.01, let window = v.window else { return 0 }
        let bounds = v.bounds
        guard bounds.width > 0, bounds.height > 0 else { return 0 }
        // Convert the view's bounds to window coordinates and intersect with the
        // window's bounds to compute the actually visible area on-screen.
        let rectInWindow = v.convert(bounds, to: window)
        let visibleRect = rectInWindow.intersection(window.bounds)
        guard !visibleRect.isNull, visibleRect.width > 0, visibleRect.height > 0 else { return 0 }
        return max(0, visibleRect.width) * max(0, visibleRect.height)
      }

    return all
      .sorted { visibleArea($0) > visibleArea($1) }
      .first(where: { visibleArea($0) > 0 }) ?? all[0]
  }

  private static let viewportTextJS = """
    (function() {
      try {
      function sanitizeText(s) {
        if (!s) return "";
        // Replace disruptive whitespace without changing length.
        return String(s).replace(/\r/g, ' ').replace(/\n/g, ' ').replace(/\t/g, ' ');
      }

      function countLeadingWhitespace(s) {
        var i = 0;
        while (i < s.length) {
          var c = s.charAt(i);
          var isNbsp = !!c && c.charCodeAt(0) === 160;
          if (c === ' ' || isNbsp) { i++; continue; }
          break;
        }
        return i;
      }

      function countTrailingWhitespace(s) {
        var i = s.length;
        while (i > 0) {
          var c = s.charAt(i - 1);
          var isNbsp = !!c && c.charCodeAt(0) === 160;
          if (c === ' ' || isNbsp) { i--; continue; }
          break;
        }
        return s.length - i;
      }

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

      function clamp(v, lo, hi) {
        return Math.max(lo, Math.min(hi, v));
      }

      function caretAt(x, y, vw, vh) {
        var cx = clamp(x, 1, Math.max(1, vw - 1));
        var cy = clamp(y, 1, Math.max(1, vh - 1));
        return caretFromPoint(cx, cy);
      }

      function preLengthForCaret(caret) {
        if (!caret) return null;
        try {
          var r = document.createRange();
          r.selectNodeContents(document.body);
          r.setEnd(caret.startContainer, caret.startOffset);
          return sanitizeText(r.toString()).length;
        } catch (e) {
          return null;
        }
      }

      function rectIntersectsViewport(rect, vw, vh) {
        if (!rect) return false;
        var left = rect.left, right = rect.right, top = rect.top, bottom = rect.bottom;
        if (!(right > 0 && bottom > 0 && left < vw && top < vh)) return false;
        var w = rect.width || (right - left);
        var h = rect.height || (bottom - top);
        return (w > 0.1 && h > 0.1);
      }

      function isCharVisible(textNode, charOffset, vw, vh) {
        try {
          if (!textNode || textNode.nodeType !== Node.TEXT_NODE) return false;
          var text = textNode.nodeValue || "";
          if (charOffset < 0 || charOffset >= text.length) return false;
          var r = document.createRange();
          r.setStart(textNode, charOffset);
          r.setEnd(textNode, charOffset + 1);
          var rects = r.getClientRects();
          if (!rects || rects.length === 0) return false;
          for (var i = 0; i < rects.length; i++) {
            if (rectIntersectsViewport(rects[i], vw, vh)) return true;
          }
          return false;
        } catch (e) {
          return false;
        }
      }

      function makeCaret(node, offset) {
        try {
          var r = document.createRange();
          r.setStart(node, offset);
          r.collapse(true);
          return r;
        } catch (e) {
          return null;
        }
      }

      function createTextWalker() {
        return document.createTreeWalker(
          document.body,
          NodeFilter.SHOW_TEXT,
          {
            acceptNode: function(n) {
              var v = n && n.nodeValue;
              return (v && v.length > 0) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
            }
          }
        );
      }

      function firstTextInOrAfter(walker, container) {
        if (!container) return null;
        if (container.nodeType === Node.TEXT_NODE && (container.nodeValue || '').length > 0) return container;
        walker.currentNode = container;
        var n = walker.firstChild();
        if (n) return n;
        n = walker.nextNode();
        return n || null;
      }

      function nextTextNode(walker, fromTextNode) {
        if (!fromTextNode) return null;
        walker.currentNode = fromTextNode;
        var n = walker.nextNode();
        return n || null;
      }

      function extendEndCaretToLastVisibleChar(caret, vw, vh, maxSteps) {
        try {
          if (!caret) return null;
          var steps = 0;
          var limit = (typeof maxSteps === 'number' && maxSteps > 0) ? maxSteps : 96;

          var walker = createTextWalker();

          var node = caret.startContainer;
          var offset = caret.startOffset;

          if (!node) return caret;
          if (node.nodeType !== Node.TEXT_NODE) {
            var first = firstTextInOrAfter(walker, node);
            if (!first) return caret;
            node = first;
            offset = 0;
          }

          while (steps < limit) {
            var text = node.nodeValue || "";
            if (offset >= text.length) {
              var next = nextTextNode(walker, node);
              if (!next) break;
              node = next;
              offset = 0;
              continue;
            }

            if (!isCharVisible(node, offset, vw, vh)) break;
            offset = offset + 1;
            steps++;
          }

          return makeCaret(node, offset) || caret;
        } catch (e) {
          return caret;
        }
      }

      function isWordChar(c) {
        return !!c && /[A-Za-z0-9]/.test(c);
      }

      function snapStartForwardOutOfWord(text, start) {
        var i = start;
        var limit = 64;
        while (limit-- > 0 && i < text.length && i > 0 && isWordChar(text.charAt(i)) && isWordChar(text.charAt(i - 1))) {
          i++;
        }
        return i;
      }

      function snapEndForwardToWordEnd(text, end) {
        var i = end;
        var limit = 64;
        while (limit-- > 0 && i < text.length && i > 0 && isWordChar(text.charAt(i - 1)) && isWordChar(text.charAt(i))) {
          i++;
        }
        return i;
      }

      var vw = window.innerWidth || document.documentElement.clientWidth || 0;
      var vh = window.innerHeight || document.documentElement.clientHeight || 0;

      var fullRange = document.createRange();
      fullRange.selectNodeContents(document.body);
      var fullText = sanitizeText(fullRange.toString() || "");

      var margin = Math.max(8, Math.floor(Math.min(vw, vh) * 0.03));

      var startCandidates = [
        [margin, margin],
        [Math.floor(vw * 0.25), margin],
        [Math.floor(vw * 0.5), margin],
        [margin, Math.floor(vh * 0.25)],
      ];

      var endCandidates = [
        [margin, vh - margin],
        [Math.floor(vw * 0.25), vh - margin],
        [vw - margin, vh - margin],
        [Math.floor(vw * 0.75), vh - margin],
        [Math.floor(vw * 0.5), vh - margin],
        [vw - margin, Math.floor(vh * 0.75)],
      ];

      var startLen = null;
      for (var si = 0; si < startCandidates.length; si++) {
        var pt = startCandidates[si];
        var c = caretAt(pt[0], pt[1], vw, vh);
        var l = preLengthForCaret(c);
        if (l === null) continue;
        if (startLen === null || l < startLen) {
          startLen = l;
        }
      }

      var endCaret = null;
      var endLen = null;
      for (var ei = 0; ei < endCandidates.length; ei++) {
        var pt2 = endCandidates[ei];
        var c2 = caretAt(pt2[0], pt2[1], vw, vh);
        var l2 = preLengthForCaret(c2);
        if (l2 === null) continue;
        if (endLen === null || l2 > endLen) {
          endLen = l2;
          endCaret = c2;
        }
      }

      // Make the end boundary inclusive of the last actually-visible glyphs.
      if (endCaret) {
        var extendedEnd = extendEndCaretToLastVisibleChar(endCaret, vw, vh, 96);
        var extendedLen = preLengthForCaret(extendedEnd);
        if (extendedLen !== null) {
          endCaret = extendedEnd;
          endLen = extendedLen;
        }
      }

      if (!endCaret || startLen === null || endLen === null) {
        var lead0 = countLeadingWhitespace(fullText);
        var trail0 = countTrailingWhitespace(fullText);
        var total0 = Math.max(0, fullText.length - lead0 - trail0);
        return JSON.stringify({ start: 0, end: 0, totalChars: total0, text: "" });
      }

      var start = startLen;
      var end = endLen;

      if (end < start) { var t = start; start = end; end = t; }

      var lead = countLeadingWhitespace(fullText);
      var trail = countTrailingWhitespace(fullText);
      var basisText = fullText.substring(lead, Math.max(lead, fullText.length - trail));
      var totalChars = basisText.length;

      start = Math.max(0, Math.min(totalChars, start - lead));
      end = Math.max(start, Math.min(totalChars, end - lead));

      var slice = basisText.substring(start, end);
      start = Math.max(0, Math.min(totalChars, start + countLeadingWhitespace(slice)));
      end = Math.max(start, Math.min(totalChars, end - countTrailingWhitespace(slice)));

      start = Math.max(0, Math.min(totalChars, snapStartForwardOutOfWord(basisText, start)));
      end = Math.max(start, Math.min(totalChars, snapEndForwardToWordEnd(basisText, end)));

      slice = basisText.substring(start, end);
      start = Math.max(0, Math.min(totalChars, start + countLeadingWhitespace(slice)));
      end = Math.max(start, Math.min(totalChars, end - countTrailingWhitespace(slice)));

      var text = basisText.substring(start, end);
      return JSON.stringify({ start: start, end: end, totalChars: totalChars, text: text });
      } catch (e) {
        try { return JSON.stringify({ __error: String(e && e.stack ? e.stack : e) }); } catch (e2) { return JSON.stringify({ __error: 'unknown' }); }
      }
    })();
  """
}
