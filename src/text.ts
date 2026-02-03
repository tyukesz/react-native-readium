import { NativeModules, Platform } from 'react-native';
import type { RefObject } from 'react';
import { requireReactTag } from './utils/requireReactTag';

export type VisibleTextRange = {
  href: string;
  start: number;
  end: number;
  totalChars: number;
  text?: string;
  isTruncated?: boolean;
  rangeSource?: 'approx' | 'viewport';
  /** What was requested via options.source (when surfaced by native). */
  requestedRangeSource?: 'approx' | 'viewport';
  /** Native diagnostic when viewport extraction fails and falls back to approx. */
  viewportFailureReason?: string;
  /** Publication position number (when available on native). */
  position?: number;
};

export type GetVisibleTextRangeOptions = {
  includeText?: boolean;
  maxTextLength?: number;
  /**
   * "approx" uses sentence/segment indices; "viewport" queries the rendered WebView DOM.
   * When "viewport", start/end/totalChars are based on DOM text order.
   * Returned `text` is JS-friendly: `\r`, `\n`, and `\t` are replaced with spaces.
   */
  source?: 'approx' | 'viewport';
};

type NativeTextModule = {
  getVisibleTextRange: (
    reactTag: number,
    options?: GetVisibleTextRangeOptions
  ) => Promise<VisibleTextRange>;
};

const NativeText: NativeTextModule | undefined = (NativeModules as any)
  ?.TextModule;

export async function getVisibleTextRange(
  viewRef: RefObject<any> | any,
  options?: GetVisibleTextRangeOptions
): Promise<VisibleTextRange> {
  if (Platform.OS === 'web') {
    throw new Error('Visible text range is not implemented on web yet');
  }
  if (!NativeText?.getVisibleTextRange) {
    throw new Error('Native TextModule is not available');
  }
  return NativeText.getVisibleTextRange(requireReactTag(viewRef), options);
}
