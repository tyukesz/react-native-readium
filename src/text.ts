import { NativeModules, findNodeHandle, Platform } from 'react-native';
import type { RefObject } from 'react';

export type VisibleTextRange = {
  href: string;
  start: number;
  end: number;
  totalChars: number;
  text?: string;
  isTruncated?: boolean;
  rangeSource?: 'approx' | 'viewport';
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

const NativeText: NativeTextModule | undefined = (NativeModules as any)?.TextModule;

function requireReactTag(viewRef: RefObject<any> | any): number {
  const node = viewRef && 'current' in viewRef ? viewRef.current : viewRef;
  const reactTag = findNodeHandle(node);
  if (!reactTag) {
    throw new Error('Could not resolve reactTag for ReadiumView');
  }
  return reactTag;
}

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

export async function getVisibleCharacterRange(
  viewRef: RefObject<any> | any
): Promise<Pick<VisibleTextRange, 'href' | 'start' | 'end' | 'totalChars'>> {
  const { href, start, end, totalChars } = await getVisibleTextRange(viewRef, {
    includeText: false,
  });
  return { href, start, end, totalChars };
}
