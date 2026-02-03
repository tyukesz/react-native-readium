import Foundation
import UIKit

struct HighlightStyleParser {
  struct HighlightStyleConfig {
    let tint: UIColor
    let isActive: Bool
  }

  static func parse(style: NSDictionary?) -> HighlightStyleConfig {
    let defaultTint = UIColor.systemYellow.withAlphaComponent(0.55)
    let defaultIsActive = true

    guard let style = style else {
      return HighlightStyleConfig(tint: defaultTint, isActive: defaultIsActive)
    }

    let isActive: Bool
    if let b = style["isActive"] as? Bool {
      isActive = b
    } else if let n = style["isActive"] as? NSNumber {
      isActive = n.boolValue
    } else {
      isActive = defaultIsActive
    }

    let tintValue = style["tint"]
    if let tintString = tintValue as? String {
      return HighlightStyleConfig(tint: colorFromTintString(tintString) ?? defaultTint, isActive: isActive)
    }

    if let tintNSString = tintValue as? NSString {
      return HighlightStyleConfig(tint: colorFromTintString(tintNSString as String) ?? defaultTint, isActive: isActive)
    }

    return HighlightStyleConfig(tint: defaultTint, isActive: isActive)
  }

  private static func colorFromTintString(_ tintString: String) -> UIColor? {
    var s = tintString.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.hasPrefix("#") {
      s.removeFirst()
    }
    if s.lowercased().hasPrefix("0x") {
      s = String(s.dropFirst(2))
    }

    guard s.count == 6 || s.count == 8 else {
      return nil
    }

    guard let raw = UInt64(s, radix: 16) else {
      return nil
    }

    let argb: UInt64 = (s.count == 6) ? ((UInt64(0xFF) << 24) | raw) : (raw & 0xFFFFFFFF)
    let a = CGFloat((argb >> 24) & 0xFF) / 255.0
    let r = CGFloat((argb >> 16) & 0xFF) / 255.0
    let g = CGFloat((argb >> 8) & 0xFF) / 255.0
    let b = CGFloat(argb & 0xFF) / 255.0
    return UIColor(red: r, green: g, blue: b, alpha: a)
  }
}
