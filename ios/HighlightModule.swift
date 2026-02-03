import Foundation
import React

@objc(HighlightModule)
class HighlightModule: NSObject, RCTBridgeModule {
  @objc var bridge: RCTBridge!

  static func moduleName() -> String! {
    return "HighlightModule"
  }

  static func requiresMainQueueSetup() -> Bool {
    // Uses UIManager view lookup.
    return true
  }

  private func withReadiumView(
    _ reactTag: NSNumber,
    _ body: @escaping (ReadiumView) -> Void
  ) {
    DispatchQueue.main.async {
      guard let uiManager = self.bridge.uiManager else { return }
      guard let view = uiManager.view(forReactTag: reactTag) as? ReadiumView else { return }
      body(view)
    }
  }

  private func withReadiumViewOrReject(
    _ reactTag: NSNumber,
    rejecter: @escaping RCTPromiseRejectBlock,
    _ body: @escaping (ReadiumView) -> Void
  ) {
    DispatchQueue.main.async {
      guard let uiManager = self.bridge.uiManager else {
        rejecter("no_ui_manager", "UIManager not available", nil)
        return
      }
      guard let view = uiManager.view(forReactTag: reactTag) as? ReadiumView else {
        rejecter("not_found", "ReadiumView not found", nil)
        return
      }
      body(view)
    }
  }

  @objc(highlightRange:href:startProgression:endProgression:)
  func highlightRange(
    _ reactTag: NSNumber,
    href: String,
    startProgression: NSNumber,
    endProgression: NSNumber
  ) {
    withReadiumView(reactTag) { view in
      view.highlightRange(
        href: href,
        startProgression: startProgression.doubleValue,
        endProgression: endProgression.doubleValue
      )
    }
  }

  @objc(highlightRangeWithStyle:href:startProgression:endProgression:style:)
  func highlightRangeWithStyle(
    _ reactTag: NSNumber,
    href: String,
    startProgression: NSNumber,
    endProgression: NSNumber,
    style: NSDictionary
  ) {
    withReadiumView(reactTag) { view in
      view.highlightRangeWithStyle(
        href: href,
        startProgression: startProgression.doubleValue,
        endProgression: endProgression.doubleValue,
        style: style
      )
    }
  }

  @objc(clearHighlight:)
  func clearHighlight(_ reactTag: NSNumber) {
    withReadiumView(reactTag) { view in
      view.clearHighlight()
    }
  }

  @objc(highlightSentence:href:sentenceIndex:)
  func highlightSentence(
    _ reactTag: NSNumber,
    href: String,
    sentenceIndex: NSNumber
  ) {
    withReadiumView(reactTag) { view in
      view.highlightSentence(href: href, sentenceIndex: sentenceIndex.intValue)
    }
  }

  @objc(highlightSentenceWithStyle:href:sentenceIndex:style:)
  func highlightSentenceWithStyle(
    _ reactTag: NSNumber,
    href: String,
    sentenceIndex: NSNumber,
    style: NSDictionary
  ) {
    withReadiumView(reactTag) { view in
      view.highlightSentenceWithStyle(
        href: href,
        sentenceIndex: sentenceIndex.intValue,
        style: style
      )
    }
  }

  @objc(getChapterSentencePage:href:offset:limit:resolver:rejecter:)
  func getChapterSentencePage(
    _ reactTag: NSNumber,
    href: String,
    offset: NSNumber,
    limit: NSNumber,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    withReadiumViewOrReject(reactTag, rejecter: rejecter) { view in
      view.getChapterSentencePage(
        href: href,
        offset: offset.intValue,
        limit: limit.intValue
      ) { total, items in
        resolver([
          "total": total,
          "items": items,
        ])
      }
    }
  }

  @objc(getChapterSentences:href:resolver:rejecter:)
  func getChapterSentences(
    _ reactTag: NSNumber,
    href: String,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    withReadiumViewOrReject(reactTag, rejecter: rejecter) { view in
      view.getChapterSentences(href: href) { sentences in
        resolver(sentences)
      }
    }
  }

  @objc(getSentenceIndexFromProgression:href:progression:resolver:rejecter:)
  func getSentenceIndexFromProgression(
    _ reactTag: NSNumber,
    href: String,
    progression: NSNumber,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    withReadiumViewOrReject(reactTag, rejecter: rejecter) { view in
      view.getSentenceIndexFromProgression(
        href: href,
        progression: progression.doubleValue
      ) { index in
        resolver(index)
      }
    }
  }

  @objc(highlightSentenceFromProgression:href:progression:resolver:rejecter:)
  func highlightSentenceFromProgression(
    _ reactTag: NSNumber,
    href: String,
    progression: NSNumber,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    withReadiumViewOrReject(reactTag, rejecter: rejecter) { view in
      view.highlightSentenceFromProgression(
        href: href,
        progression: progression.doubleValue
      ) { index in
        resolver(index)
      }
    }
  }

  @objc(highlightSentenceFromProgressionWithStyle:href:progression:style:resolver:rejecter:)
  func highlightSentenceFromProgressionWithStyle(
    _ reactTag: NSNumber,
    href: String,
    progression: NSNumber,
    style: NSDictionary,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    withReadiumViewOrReject(reactTag, rejecter: rejecter) { view in
      view.highlightSentenceFromProgression(
        href: href,
        progression: progression.doubleValue,
        style: style
      ) { index in
        resolver(index)
      }
    }
  }
}
