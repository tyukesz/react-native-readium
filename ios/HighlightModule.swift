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

  @objc(highlightLocator:location:)
  func highlightLocator(
    _ reactTag: NSNumber,
    location: NSDictionary
  ) {
    withReadiumView(reactTag) { view in
      view.highlightLocator(location: location)
    }
  }

  @objc(highlightLocatorWithStyle:location:style:)
  func highlightLocatorWithStyle(
    _ reactTag: NSNumber,
    location: NSDictionary,
    style: NSDictionary
  ) {
    withReadiumView(reactTag) { view in
      view.highlightLocatorWithStyle(location: location, style: style)
    }
  }

  @objc(getChapterRawText:href:resolver:rejecter:)
  func getChapterRawText(
    _ reactTag: NSNumber,
    href: String,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    withReadiumViewOrReject(reactTag, rejecter: rejecter) { view in
      view.getChapterRawText(href: href) { result in
        resolver(result)
      }
    }
  }
}
