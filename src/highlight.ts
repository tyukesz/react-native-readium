import { NativeModules, Platform } from 'react-native';
import type { RefObject } from 'react';
import { requireReactTag } from './utils/requireReactTag';
import type { Locator } from './interfaces';
import {
  getOrBuildSentenceIndex,
  getSentenceIndexFromProgressionSync,
} from './sentences';
import type { SentenceSplitter } from './sentences';

export type { SentenceSplitter } from './sentences';
export { clearSentenceCache } from './sentences';

export type HighlightRangeParams = {
  href: string;
  startProgression: number;
  endProgression: number;
  style?: HighlightStyle;
};

export type HighlightStyle = {
  /**
   * Hex color string: "#RRGGBB", "#AARRGGBB", or "0x...".
   */
  tint?: string;
  /**
   * Whether the decoration is "active" (platform-dependent behavior).
   */
  isActive?: boolean;
};

export type SentencePageItem = {
  index: number;
  text: string;
  locator?: Locator;
};

export type SentencePage = {
  total: number;
  items: SentencePageItem[];
};

export type GetChapterSentencePageParams = {
  href: string;
  offset?: number;
  limit?: number;
  splitter: SentenceSplitter;
};

export type GetSentenceIndexFromProgressionParams = {
  href: string;
  progression: number;
  splitter: SentenceSplitter;
};

type NativeHighlightModule = {
  highlightRange: (
    reactTag: number,
    href: string,
    startProgression: number,
    endProgression: number
  ) => void;
  highlightRangeWithStyle?: (
    reactTag: number,
    href: string,
    startProgression: number,
    endProgression: number,
    style: HighlightStyle
  ) => void;
  highlightLocator?: (reactTag: number, locator: Locator) => void;
  highlightLocatorWithStyle?: (
    reactTag: number,
    locator: Locator,
    style: HighlightStyle
  ) => void;
  clearHighlight: (reactTag: number) => void;
};

const NativeHighlight: NativeHighlightModule | undefined = (
  NativeModules as any
)?.HighlightModule;

function validateHighlightStyle(style?: HighlightStyle): void {
  if (!style) return;
  if (style.tint != null && typeof style.tint !== 'string') {
    throw new Error(
      'style.tint must be a string ("#RRGGBB", "#AARRGGBB", or "0x...")'
    );
  }
}

// requireReactTag lives in src/utils/requireReactTag.ts

export function highlightRange(
  viewRef: RefObject<any> | any,
  params: HighlightRangeParams
): void {
  if (Platform.OS === 'web') {
    throw new Error('Highlighting is not implemented on web yet');
  }

  if (!NativeHighlight?.highlightRange) {
    throw new Error('Native HighlightModule is not available');
  }

  if (!params?.href) {
    throw new Error('href is required');
  }

  const start = Number(params.startProgression);
  const end = Number(params.endProgression);
  if (!Number.isFinite(start) || !Number.isFinite(end)) {
    throw new Error('Invalid startProgression/endProgression');
  }

  const style = params.style;
  validateHighlightStyle(style);
  if (style && NativeHighlight.highlightRangeWithStyle) {
    NativeHighlight.highlightRangeWithStyle(
      requireReactTag(viewRef),
      params.href,
      start,
      end,
      style
    );
    return;
  }

  if (style && !NativeHighlight.highlightRangeWithStyle) {
    console.warn(
      '[react-native-readium] highlightRange: native highlightRangeWithStyle is not available; falling back to default highlight style.'
    );
  }

  NativeHighlight.highlightRange(
    requireReactTag(viewRef),
    params.href,
    start,
    end
  );
}

export function clearHighlight(viewRef: RefObject<any> | any): void {
  if (Platform.OS === 'web') {
    return;
  }

  if (!NativeHighlight?.clearHighlight) {
    throw new Error('Native HighlightModule is not available');
  }

  NativeHighlight.clearHighlight(requireReactTag(viewRef));
}

export function highlightLocator(
  viewRef: RefObject<any> | any,
  locator: Locator,
  style?: HighlightStyle
): void {
  if (Platform.OS === 'web') {
    throw new Error('Highlighting is not implemented on web yet');
  }

  if (!NativeHighlight?.highlightLocator) {
    throw new Error('Native HighlightModule.highlightLocator is not available');
  }

  const href = (locator as any)?.href?.trim?.() ?? '';
  if (!href) {
    throw new Error('locator.href is required');
  }

  validateHighlightStyle(style);
  if (style && NativeHighlight.highlightLocatorWithStyle) {
    NativeHighlight.highlightLocatorWithStyle(
      requireReactTag(viewRef),
      locator,
      style
    );
    return;
  }

  if (style && !NativeHighlight.highlightLocatorWithStyle) {
    console.warn(
      '[react-native-readium] highlightLocator: native highlightLocatorWithStyle is not available; falling back to default highlight style.'
    );
  }

  NativeHighlight.highlightLocator(requireReactTag(viewRef), locator);
}

export async function getChapterSentences(
  viewRef: RefObject<any> | any,
  href: string,
  splitter: SentenceSplitter
): Promise<string[]> {
  const index = await getOrBuildSentenceIndex(viewRef, href, splitter);
  return index.sentences.map((s) => s.text);
}

export async function getChapterSentencePage(
  viewRef: RefObject<any> | any,
  params: GetChapterSentencePageParams
): Promise<SentencePage>;
export async function getChapterSentencePage(
  viewRef: RefObject<any> | any,
  href: string,
  splitter: SentenceSplitter,
  offset?: number,
  limit?: number
): Promise<SentencePage>;
export async function getChapterSentencePage(
  viewRef: RefObject<any> | any,
  hrefOrParams: string | GetChapterSentencePageParams,
  splitterOrOffset?: SentenceSplitter | number,
  offset?: number,
  limit?: number
): Promise<SentencePage> {
  let resolvedHref: string;
  let resolvedSplitter: SentenceSplitter;
  let resolvedOffset: number;
  let resolvedLimit: number;

  if (typeof hrefOrParams === 'string') {
    resolvedHref = hrefOrParams;
    resolvedSplitter = splitterOrOffset as SentenceSplitter;
    resolvedOffset = offset ?? 0;
    resolvedLimit = limit ?? 0x7fffffff;
  } else {
    resolvedHref = hrefOrParams.href;
    resolvedSplitter = hrefOrParams.splitter;
    resolvedOffset = hrefOrParams.offset ?? 0;
    resolvedLimit = hrefOrParams.limit ?? 0x7fffffff;
  }

  const index = await getOrBuildSentenceIndex(
    viewRef,
    resolvedHref,
    resolvedSplitter
  );
  const total = index.sentences.length;
  const safeOffset = Math.max(0, Math.min(resolvedOffset, total));
  const safeLimit = Math.max(0, resolvedLimit);

  const items: SentencePageItem[] =
    safeLimit === 0
      ? []
      : index.sentences.slice(safeOffset, safeOffset + safeLimit).map((s) => ({
          index: s.index,
          text: s.text,
          locator: s.locator,
        }));

  return { total, items };
}

export async function getSentenceIndexFromProgression(
  viewRef: RefObject<any> | any,
  params: GetSentenceIndexFromProgressionParams
): Promise<number>;
export async function getSentenceIndexFromProgression(
  viewRef: RefObject<any> | any,
  href: string,
  progression: number,
  splitter: SentenceSplitter
): Promise<number>;
export async function getSentenceIndexFromProgression(
  viewRef: RefObject<any> | any,
  hrefOrParams: string | GetSentenceIndexFromProgressionParams,
  progression?: number,
  splitter?: SentenceSplitter
): Promise<number> {
  let resolvedHref: string;
  let resolvedProgression: number;
  let resolvedSplitter: SentenceSplitter;

  if (typeof hrefOrParams === 'string') {
    resolvedHref = hrefOrParams;
    resolvedProgression = progression!;
    resolvedSplitter = splitter!;
  } else {
    resolvedHref = hrefOrParams.href;
    resolvedProgression = hrefOrParams.progression;
    resolvedSplitter = hrefOrParams.splitter;
  }

  const cleanHref = resolvedHref?.trim();
  if (!cleanHref) {
    throw new Error('href is required');
  }

  const p = Number(resolvedProgression);
  if (!Number.isFinite(p)) {
    throw new Error('progression must be a finite number');
  }

  const index = await getOrBuildSentenceIndex(
    viewRef,
    cleanHref,
    resolvedSplitter
  );

  // Extract position progressions from the sentence index's locators
  // We need the raw positionEntries for the algorithm, but they're not stored
  // in the sentence index. Use the sentence's pageStartProgression values
  // to reconstruct the unique position boundaries.
  const positionProgressions = [
    ...new Set(index.sentences.map((s) => s.pageStartProgression)),
  ].sort((a, b) => a - b);

  return getSentenceIndexFromProgressionSync(
    index,
    p,
    positionProgressions
  );
}
