import { NativeModules, Platform } from 'react-native';
import type { RefObject } from 'react';
import { requireReactTag } from './utils/requireReactTag';
import type { Locator } from './interfaces';

export type HighlightRangeParams = {
  href: string;
  startProgression: number;
  endProgression: number;
  style?: HighlightStyle;
};

export type HighlightSentenceParams = {
  href: string;
  sentenceIndex: number;
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
};

export type GetSentenceIndexFromProgressionParams = {
  href: string;
  progression: number;
};

export type HighlightSentenceFromProgressionParams = {
  href: string;
  progression: number;
  style?: HighlightStyle;
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
  highlightSentence?: (
    reactTag: number,
    href: string,
    sentenceIndex: number
  ) => void | Promise<void>;
  highlightSentenceWithStyle?: (
    reactTag: number,
    href: string,
    sentenceIndex: number,
    style: HighlightStyle
  ) => void | Promise<void>;
  getChapterSentences?: (reactTag: number, href: string) => Promise<string[]>;
  getChapterSentencePage?: (
    reactTag: number,
    href: string,
    offset: number,
    limit: number
  ) => Promise<SentencePage>;
  getSentenceIndexFromProgression?: (
    reactTag: number,
    href: string,
    progression: number
  ) => Promise<number>;
  highlightSentenceFromProgression?: (
    reactTag: number,
    href: string,
    progression: number
  ) => Promise<number>;
  highlightSentenceFromProgressionWithStyle?: (
    reactTag: number,
    href: string,
    progression: number,
    style: HighlightStyle
  ) => Promise<number>;
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
    // Keep backward compatibility but make the silent fallback visible.
    // Without the WithStyle native method, iOS will use its default highlight tint.

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
  href: string
): Promise<string[]> {
  if (!NativeHighlight?.getChapterSentences) {
    throw new Error(
      'Native HighlightModule.getChapterSentences is not available'
    );
  }

  const cleanHref = href?.trim();
  if (!cleanHref) {
    throw new Error('href is required');
  }

  return NativeHighlight.getChapterSentences(
    requireReactTag(viewRef),
    cleanHref
  );
}

export async function getChapterSentencePage(
  viewRef: RefObject<any> | any,
  params: GetChapterSentencePageParams
): Promise<SentencePage>;
export async function getChapterSentencePage(
  viewRef: RefObject<any> | any,
  href: string,
  offset?: number,
  limit?: number
): Promise<SentencePage>;
export async function getChapterSentencePage(
  viewRef: RefObject<any> | any,
  hrefOrParams: string | GetChapterSentencePageParams,
  offset?: number,
  limit?: number
): Promise<SentencePage> {
  if (!NativeHighlight?.getChapterSentencePage) {
    throw new Error(
      'Native HighlightModule.getChapterSentencePage is not available'
    );
  }

  const resolvedHref =
    typeof hrefOrParams === 'string' ? hrefOrParams : hrefOrParams?.href;
  const resolvedOffset =
    typeof hrefOrParams === 'string' ? offset : hrefOrParams?.offset;
  const resolvedLimit =
    typeof hrefOrParams === 'string' ? limit : hrefOrParams?.limit;

  const cleanHref = resolvedHref?.trim();
  if (!cleanHref) {
    throw new Error('href is required');
  }

  // If limit is omitted, return all sentences (no default page size).
  // We use a large safe int since the native bridge expects an Int.
  const nativeLimit = resolvedLimit == null ? 0x7fffffff : Number(resolvedLimit);

  return NativeHighlight.getChapterSentencePage(
    requireReactTag(viewRef),
    cleanHref,
    Number(resolvedOffset ?? 0),
    nativeLimit
  );
}

export async function getSentenceIndexFromProgression(
  viewRef: RefObject<any> | any,
  params: GetSentenceIndexFromProgressionParams
): Promise<number>;
export async function getSentenceIndexFromProgression(
  viewRef: RefObject<any> | any,
  href: string,
  progression: number
): Promise<number>;
export async function getSentenceIndexFromProgression(
  viewRef: RefObject<any> | any,
  hrefOrParams: string | GetSentenceIndexFromProgressionParams,
  progression?: number
): Promise<number> {
  if (!NativeHighlight?.getSentenceIndexFromProgression) {
    throw new Error(
      'Native HighlightModule.getSentenceIndexFromProgression is not available'
    );
  }

  const resolvedHref =
    typeof hrefOrParams === 'string' ? hrefOrParams : hrefOrParams?.href;
  const resolvedProgression =
    typeof hrefOrParams === 'string' ? progression : hrefOrParams?.progression;

  const cleanHref = resolvedHref?.trim();
  if (!cleanHref) {
    throw new Error('href is required');
  }

  const p = Number(resolvedProgression);
  if (!Number.isFinite(p)) {
    throw new Error('progression must be a finite number');
  }

  return NativeHighlight.getSentenceIndexFromProgression(
    requireReactTag(viewRef),
    cleanHref,
    p
  );
}

export async function highlightSentenceFromProgression(
  viewRef: RefObject<any> | any,
  params: HighlightSentenceFromProgressionParams
): Promise<number>;
export async function highlightSentenceFromProgression(
  viewRef: RefObject<any> | any,
  href: string,
  progression: number
): Promise<number>;
export async function highlightSentenceFromProgression(
  viewRef: RefObject<any> | any,
  hrefOrParams: string | HighlightSentenceFromProgressionParams,
  progression?: number
): Promise<number> {
  const reactTag = requireReactTag(viewRef);

  const resolvedHref =
    typeof hrefOrParams === 'string' ? hrefOrParams : hrefOrParams?.href;
  const cleanHref = resolvedHref?.trim();
  if (!cleanHref) {
    throw new Error('href is required');
  }

  const resolvedProgression =
    typeof hrefOrParams === 'string' ? progression : hrefOrParams?.progression;
  const p = Number(resolvedProgression);
  if (!Number.isFinite(p)) {
    throw new Error('progression must be a finite number');
  }

  const style =
    typeof hrefOrParams === 'string' ? undefined : hrefOrParams?.style;
  validateHighlightStyle(style);

  if (style && NativeHighlight?.highlightSentenceFromProgressionWithStyle) {
    return NativeHighlight.highlightSentenceFromProgressionWithStyle(
      reactTag,
      cleanHref,
      p,
      style
    );
  }

  if (!NativeHighlight?.highlightSentenceFromProgression || style) {
    const idx = await getSentenceIndexFromProgression(viewRef, {
      href: cleanHref,
      progression: p,
    });
    highlightSentence(viewRef, { href: cleanHref, sentenceIndex: idx, style });
    return idx;
  }

  return NativeHighlight.highlightSentenceFromProgression(
    reactTag,
    cleanHref,
    p
  );
}

export function highlightSentence(
  viewRef: RefObject<any> | any,
  params: HighlightSentenceParams
): void {
  const reactTag = requireReactTag(viewRef);

  if (!NativeHighlight?.highlightSentence) {
    throw new Error(
      'Native HighlightModule.highlightSentence is not available'
    );
  }

  const href = params?.href?.trim();
  if (!href) {
    throw new Error('href is required');
  }

  const idx = Number(params.sentenceIndex);
  if (!Number.isInteger(idx) || idx < 0) {
    throw new Error('Invalid sentenceIndex (must be a non-negative integer)');
  }

  const style = params.style;
  validateHighlightStyle(style);
  if (style && NativeHighlight.highlightSentenceWithStyle) {
    (NativeHighlight.highlightSentenceWithStyle as any)(
      reactTag,
      href,
      idx,
      style
    );
    return;
  }

  if (style && !NativeHighlight.highlightSentenceWithStyle) {
    console.warn(
      '[react-native-readium] highlightSentence: native highlightSentenceWithStyle is not available; falling back to default highlight style.'
    );
  }

  // The native method is synchronous; ignoring potential Promise return keeps compatibility.
  (NativeHighlight.highlightSentence as any)(reactTag, href, idx);
}
