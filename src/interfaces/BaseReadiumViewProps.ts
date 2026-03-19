import type { ViewStyle } from 'react-native';
import type { Link } from './Link';
import type { Locator } from './Locator';
import type { File } from './File';
import type { PublicationReadyEvent } from './PublicationReady';
import type { TapEvent } from './TapEvent';

export type BaseReadiumViewProps = {
  file: File;
  location?: Locator | Link;
  preferences?: string; // JSON between native and JS, which we deserialise later
  hidePageNumbers?: boolean;
  /**
   * Enable or disable tap navigation on screen edges (iOS only).
   * Android uses swipe gestures for navigation and this prop has no effect.
   * @default true
   * @platform ios
   */
  enableTapNavigation?: boolean;
  /**
   * Disables text selection in the native EPUB reader when set to true.
   * @default false
   * @platform ios android
   */
  disableTextSelection?: boolean;
  style?: ViewStyle;
  onLocationChange?: (locator: Locator) => void;
  onPublicationReady?: (event: PublicationReadyEvent) => void;
  onRestrictedNavigation?: (href: string) => void;
  onTap?: (event: TapEvent) => void;
  allowedHrefs?: string[];
  paywallHTML?: string;
  ref?: any;
  height?: number;
  width?: number;
};
