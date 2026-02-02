import Foundation
import React

@objc(TextModule)
class TextModule: NSObject, RCTBridgeModule {
  @objc var bridge: RCTBridge!

  static func moduleName() -> String! {
    return "TextModule"
  }

  static func requiresMainQueueSetup() -> Bool {
    // Uses UIManager view lookup.
    return true
  }

  @objc(getVisibleTextRange:options:resolver:rejecter:)
  func getVisibleTextRange(
    _ reactTag: NSNumber,
    options: NSDictionary?,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
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

      let includeText: Bool = (options?["includeText"] as? Bool) ?? true
      let maxTextLength: Int? = (options?["maxTextLength"] as? NSNumber)?.intValue
      let source: String? = (options?["source"] as? String)

      Task { @MainActor in
        do {
          let payload = try await view.getVisibleTextRangePayload(
            includeText: includeText,
            maxTextLength: maxTextLength,
            source: source
          )
          resolver(payload)
        } catch {
          rejecter("visible_text_error", error.localizedDescription, error)
        }
      }
    }
  }
}
