import Foundation
import React

@objc(NavigationModule)
class NavigationModule: NSObject, RCTBridgeModule {
  @objc var bridge: RCTBridge!

  static func moduleName() -> String! {
    return "NavigationModule"
  }

  static func requiresMainQueueSetup() -> Bool {
    // Uses UIManager view lookup.
    return true
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

  @objc(navigateTo:location:resolver:rejecter:)
  func navigateTo(
    _ reactTag: NSNumber,
    location: NSDictionary,
    resolver: @escaping RCTPromiseResolveBlock,
    rejecter: @escaping RCTPromiseRejectBlock
  ) {
    withReadiumViewOrReject(reactTag, rejecter: rejecter) { view in
      Task { @MainActor in
        guard let navigator = view.readerViewController?.navigator else {
          // Not ready yet.
          resolver(false)
          return
        }

        guard let locator = await ReaderService.locatorFromLocation(
          location,
          view.readerViewController?.publication
        ) else {
          rejecter("invalid_location", "Could not create a Locator from the provided location", nil)
          return
        }

        let currentLocation = navigator.currentLocation
        if let currentLocation, locator.hashValue == currentLocation.hashValue {
          resolver(true)
          return
        }

        let ok = await navigator.go(to: locator, options: .animated)
        resolver(ok)
      }
    }
  }
}
