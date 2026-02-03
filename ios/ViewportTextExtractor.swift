import Foundation
import UIKit
import WebKit

@MainActor
final class ViewportTextExtractor {
  struct ViewportTextRange {
    let start: Int
    let end: Int
    let totalChars: Int
    let text: String
  }

  private(set) var lastFailureReason: String? = nil

  func extract(from root: UIView?) async -> ViewportTextRange? {
    // View hierarchy / WKWebView loading can be transient during navigation.
    // Retry a few times and try each visible WKWebView until one successfully
    // returns a valid JSON payload.
    lastFailureReason = nil

    var previousFailureReason: String? = nil
    var sameFailureCount = 0

    let maxAttempts = 25
    let baseDelayNs: UInt64 = 120_000_000

    for attempt in 0..<maxAttempts {
      var webViews = findWKWebViews(in: root)
      if webViews.isEmpty {
        webViews = findWKWebViewsInApplication()
      }

      if webViews.isEmpty {
        lastFailureReason = "WKWebView not found"
      }

      for webView in webViews {
        // During navigation, WebKit can transiently reject JS evaluation.
        // Still try, but give loading pages additional retries.
        if let range = await evaluate(in: webView) {
          return range
        }
      }

      // If we're consistently getting the same failure (especially JS exceptions),
      // don't keep waiting — it won't fix itself.
      if let reason = lastFailureReason, reason == previousFailureReason {
        sameFailureCount += 1
      } else {
        sameFailureCount = 0
        previousFailureReason = lastFailureReason
      }

      if sameFailureCount >= 2 {
        break
      }

      // Don’t sleep after the last attempt.
      if attempt + 1 < maxAttempts {
        let backoff = min(UInt64(attempt) * 30_000_000, 450_000_000)
        try? await Task.sleep(nanoseconds: baseDelayNs + backoff)
      }
    }

    return nil
  }

  private func evaluate(in webView: WKWebView) async -> ViewportTextRange? {
    return await withCheckedContinuation { continuation in
      webView.evaluateJavaScript(Self.viewportTextJS) { result, error in
        Task { @MainActor in
          guard error == nil else {
            let nsError = error as NSError?
            if let nsError {
              var detailParts: [String] = []
              if let message = nsError.userInfo["WKJavaScriptExceptionMessage"] as? String, !message.isEmpty {
                detailParts.append("message=\(message)")
              }
              if let line = nsError.userInfo["WKJavaScriptExceptionLineNumber"] {
                detailParts.append("line=\(line)")
              }
              if let column = nsError.userInfo["WKJavaScriptExceptionColumnNumber"] {
                detailParts.append("column=\(column)")
              }
              if let url = nsError.userInfo["WKJavaScriptExceptionSourceURL"] {
                detailParts.append("url=\(url)")
              }
              let details = detailParts.isEmpty ? "" : " [\(detailParts.joined(separator: ", "))]"
              self.lastFailureReason = "WKErrorDomain \(nsError.domain)(\(nsError.code)): \(nsError.localizedDescription)\(details)"
            } else {
              self.lastFailureReason = error?.localizedDescription
            }
            continuation.resume(returning: nil)
            return
          }

          guard let jsonString = result as? String,
                let data = jsonString.data(using: .utf8),
                let obj = try? JSONSerialization.jsonObject(with: data, options: []),
                let dict = obj as? [String: Any] else {
            self.lastFailureReason = "Invalid JS result"
            continuation.resume(returning: nil)
            return
          }

          // The injected JS always returns JSON; treat JS exceptions as a failure
          // so we can retry/try another WKWebView.
          if dict["__error"] != nil {
            self.lastFailureReason = (dict["__error"] as? String) ?? "JS error"
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
  }

  private func findWKWebViews(in view: UIView?) -> [WKWebView] {
    guard let view = view else { return [] }

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

    if all.isEmpty { return [] }
    if all.count == 1 { return all }

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

    // Sort by best on-screen candidate first.
    let sorted = all.sorted { visibleArea($0) > visibleArea($1) }
    // Prefer views that are actually visible, but keep a fallback list.
    let visible = sorted.filter { visibleArea($0) > 0 }
    return visible.isEmpty ? sorted : visible
  }

  private func findWKWebViewsInApplication() -> [WKWebView] {
    // Fallback: sometimes the passed root view is not the one hosting the
    // navigator yet (eg. during transitions). Scan visible windows.
    let windows: [UIWindow] = {
      if #available(iOS 13.0, *) {
        return UIApplication.shared.connectedScenes
          .compactMap { $0 as? UIWindowScene }
          .flatMap { $0.windows }
      }
      return UIApplication.shared.windows
    }()

    var all: [WKWebView] = []
    for w in windows {
      all.append(contentsOf: findWKWebViews(in: w))
    }
    return all
  }

  private static let viewportTextJS = #"""
    (function() {
      try {
        return (function() {
      var doc = document;
      var vw = 0;
      var vh = 0;
      var root = null;

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
        if (doc.caretRangeFromPoint) {
          return doc.caretRangeFromPoint(x, y);
        }
        if (doc.caretPositionFromPoint) {
          var pos = doc.caretPositionFromPoint(x, y);
          if (!pos) return null;
          var r = doc.createRange();
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
          var r = doc.createRange();
          r.selectNodeContents(root);
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
          var r = doc.createRange();
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
          var r = doc.createRange();
          r.setStart(node, offset);
          r.collapse(true);
          return r;
        } catch (e) {
          return null;
        }
      }

      function createTextWalker() {
        return doc.createTreeWalker(
          root,
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

      // Readium iOS navigator often renders the actual content inside an iframe.
      // Prefer the most-visible iframe document when available.
      try {
        var iframes = document.getElementsByTagName('iframe');
        var best = null;
        var bestArea = 0;
        for (var fi = 0; fi < iframes.length; fi++) {
          var f = iframes[fi];
          if (!f || !f.contentDocument) continue;
          var rect = f.getBoundingClientRect();
          var left = Math.max(0, rect.left);
          var right = Math.min(window.innerWidth || 0, rect.right);
          var top = Math.max(0, rect.top);
          var bottom = Math.min(window.innerHeight || 0, rect.bottom);
          var area = Math.max(0, right - left) * Math.max(0, bottom - top);
          if (area > bestArea) {
            bestArea = area;
            best = f;
          }
        }
        if (best && best.contentDocument) {
          doc = best.contentDocument;
        }
      } catch (e) {}

      var win = (doc && doc.defaultView) ? doc.defaultView : window;
      vw = win.innerWidth || (doc.documentElement && doc.documentElement.clientWidth) || 0;
      vh = win.innerHeight || (doc.documentElement && doc.documentElement.clientHeight) || 0;

      root = doc.body || doc.documentElement;
      if (!root) {
        return JSON.stringify({ start: 0, end: 0, totalChars: 0, text: "" });
      }

      var fullRange = doc.createRange();
      fullRange.selectNodeContents(root);
      var fullText = sanitizeText(fullRange.toString() || "");

      var margin = Math.max(8, Math.floor(Math.min(vw, vh) * 0.03));

      var startCandidates = [
        [margin, margin],
        [Math.floor(vw * 0.25), margin],
        [Math.floor(vw * 0.5), margin],
        [margin, Math.floor(vh * 0.25)]
      ];

      var endCandidates = [
        [margin, vh - margin],
        [Math.floor(vw * 0.25), vh - margin],
        [vw - margin, vh - margin],
        [Math.floor(vw * 0.75), vh - margin],
        [Math.floor(vw * 0.5), vh - margin],
        [vw - margin, Math.floor(vh * 0.75)]
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
        })();
      } catch (e) {
        var msg = "";
        try { msg = String(e && (e.message || e)); } catch (_) { msg = "unknown"; }
        return JSON.stringify({ start: 0, end: 0, totalChars: 0, text: "", __error: msg });
      }
    })();
  """#
}
