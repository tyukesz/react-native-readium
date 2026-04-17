import { NativeModules, Platform } from 'react-native';
import type { RefObject } from 'react';
import { requireReactTag } from './utils/requireReactTag';
import type { Locator } from './interfaces';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export type SentenceSplitter = (rawText: string) => string[];

export type SentenceEntry = {
  index: number;
  text: string;
  start: number;
  end: number;
  progression: number;
  pageStartProgression: number;
  locator: Locator;
};

export type SentenceIndex = {
  href: string;
  sentences: SentenceEntry[];
  totalChars: number;
};

// ---------------------------------------------------------------------------
// Native bridge types
// ---------------------------------------------------------------------------

type NativeSegment = {
  text: string;
  start: number;
  end: number;
  locator: any; // serialized Locator JSON
};

type NativePositionEntry = {
  progression: number;
  position: number;
  totalProgression: number;
};

type NativeChapterRawText = {
  combinedText: string;
  segments: NativeSegment[];
  positionEntries: NativePositionEntry[];
};

type NativeHighlightModule = {
  getChapterRawText?: (
    reactTag: number,
    href: string
  ) => Promise<NativeChapterRawText>;
};

const NativeHighlight: NativeHighlightModule | undefined = (
  NativeModules as any
)?.HighlightModule;

// ---------------------------------------------------------------------------
// LRU cache (max 4 entries)
// ---------------------------------------------------------------------------

const MAX_CACHE = 4;
const cache = new Map<string, SentenceIndex>();

function cacheGet(href: string): SentenceIndex | undefined {
  const entry = cache.get(href);
  if (entry) {
    // Move to end (most recently used)
    cache.delete(href);
    cache.set(href, entry);
  }
  return entry;
}

function cachePut(href: string, index: SentenceIndex): void {
  cache.delete(href);
  cache.set(href, index);
  if (cache.size > MAX_CACHE) {
    // Evict oldest (first key)
    const oldest = cache.keys().next().value;
    if (oldest !== undefined) {
      cache.delete(oldest);
    }
  }
}

export function clearSentenceCache(): void {
  cache.clear();
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const TEXT_QUOTE_CONTEXT_CHARS = 32;
const EPS = 1e-9;

// ---------------------------------------------------------------------------
// Sentence index builder
// ---------------------------------------------------------------------------

/**
 * Maps cleaned sentence texts back to offsets in the original combinedText.
 * Uses whitespace-agnostic matching: the splitter may collapse/strip whitespace,
 * so we match non-whitespace characters in order.
 */
function mapSentencesToOffsets(
  combinedText: string,
  sentenceTexts: string[]
): Array<{ text: string; start: number; end: number }> {
  const result: Array<{ text: string; start: number; end: number }> = [];
  let cursor = 0;

  for (const sentence of sentenceTexts) {
    // Skip whitespace in combinedText to find where this sentence starts
    while (cursor < combinedText.length && /\s/.test(combinedText[cursor])) {
      cursor++;
    }

    const sentenceStart = cursor;

    // Match non-whitespace characters from the sentence against combinedText
    let sentenceCharIdx = 0;
    while (sentenceCharIdx < sentence.length && cursor < combinedText.length) {
      // Skip whitespace in sentence
      if (/\s/.test(sentence[sentenceCharIdx])) {
        sentenceCharIdx++;
        continue;
      }
      // Skip whitespace in combinedText
      if (/\s/.test(combinedText[cursor])) {
        cursor++;
        continue;
      }
      // Match non-whitespace characters
      if (combinedText[cursor] === sentence[sentenceCharIdx]) {
        cursor++;
        sentenceCharIdx++;
      } else {
        // Mismatch - the splitter returned text that doesn't appear in combinedText
        throw new Error(
          `[react-native-readium] Sentence splitter mismatch at combinedText[${cursor}]='${combinedText[cursor]}' vs sentence[${sentenceCharIdx}]='${sentence[sentenceCharIdx]}'. ` +
            `Sentence: "${sentence.substring(0, 80)}..."`
        );
      }
    }

    if (sentenceCharIdx < sentence.replace(/\s/g, '').length) {
      throw new Error(
        `[react-native-readium] Sentence splitter returned text that extends beyond combinedText. ` +
          `Sentence: "${sentence.substring(0, 80)}..."`
      );
    }

    const sentenceEnd = cursor;
    result.push({ text: sentence, start: sentenceStart, end: sentenceEnd });
  }

  return result;
}

function findBoundaryIndex(
  progression: number,
  positionEntries: NativePositionEntry[]
): number {
  if (positionEntries.length === 0) return 0;
  let idx = 0;
  for (let i = 0; i < positionEntries.length; i++) {
    if (positionEntries[i].progression <= progression + EPS) {
      idx = i;
    } else {
      break;
    }
  }
  return idx;
}

function buildLocatorForSentence(
  sentenceText: string,
  sentenceStart: number,
  sentenceEnd: number,
  combinedText: string,
  segments: NativeSegment[],
  progression: number,
  positionEntries: NativePositionEntry[]
): Locator {
  // Binary search for the segment containing the sentence start.
  let lo = 0;
  let hi = segments.length - 1;
  let seg: NativeSegment | null = null;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    const s = segments[mid];
    if (sentenceStart < s.start) {
      hi = mid - 1;
    } else if (sentenceStart >= s.end) {
      lo = mid + 1;
    } else {
      seg = s;
      break;
    }
  }

  if (!seg) {
    seg = segments[0];
  }

  // TextQuote: highlight = full cleaned sentence text,
  // before/after = context from combinedText around the sentence boundaries
  const beforeStart = Math.max(0, sentenceStart - TEXT_QUOTE_CONTEXT_CHARS);
  const afterEnd = Math.min(combinedText.length, sentenceEnd + TEXT_QUOTE_CONTEXT_CHARS);
  const before = combinedText.substring(beforeStart, sentenceStart).replace(/\s+/g, ' ') || undefined;
  const highlight = sentenceText;
  const after = combinedText.substring(sentenceEnd, afterEnd).replace(/\s+/g, ' ') || undefined;

  // Clone the segment locator and rewrite
  const baseLocator = { ...seg.locator };
  const locations = { ...(baseLocator.locations || {}) };

  locations.progression = Math.max(0, Math.min(1, progression));

  // Resolve position and totalProgression from positionEntries
  if (positionEntries.length > 0) {
    const bIdx = findBoundaryIndex(progression, positionEntries);
    const entry = positionEntries[bIdx];
    if (entry) {
      locations.position = entry.position;
      locations.totalProgression = entry.totalProgression;
    }
  }

  baseLocator.locations = locations;
  baseLocator.text = { before, highlight, after };

  return baseLocator as Locator;
}

function buildSentenceIndex(
  href: string,
  rawText: NativeChapterRawText,
  splitter: SentenceSplitter
): SentenceIndex {
  const { combinedText, segments, positionEntries } = rawText;

  if (!combinedText || combinedText.length === 0) {
    return { href, sentences: [], totalChars: 0 };
  }

  const sentenceTexts = splitter(combinedText);
  if (sentenceTexts.length === 0) {
    return { href, sentences: [], totalChars: 0 };
  }

  const mapped = mapSentencesToOffsets(combinedText, sentenceTexts);
  const totalChars = combinedText.length;

  // Compute progressions (same algorithm as native finalizeSentenceIndex)
  const progressions = positionEntries.map((e) => e.progression);

  type Draft = {
    index: number;
    start: number;
    end: number;
    text: string;
    boundaryIndex: number;
  };

  const drafts: Draft[] = mapped.map((m, idx) => {
    const charProgression =
      totalChars > 0
        ? Math.max(0, Math.min(1, m.start / totalChars))
        : 0;
    const boundaryIndex =
      progressions.length === 0
        ? 0
        : findBoundaryIndex(charProgression, positionEntries);
    return {
      index: idx,
      start: m.start,
      end: m.end,
      text: m.text,
      boundaryIndex,
    };
  });

  // Group by boundaryIndex
  const grouped = new Map<number, Draft[]>();
  for (const d of drafts) {
    const group = grouped.get(d.boundaryIndex);
    if (group) {
      group.push(d);
    } else {
      grouped.set(d.boundaryIndex, [d]);
    }
  }

  const sentences: SentenceEntry[] = drafts.map((d) => {
    const pageStart =
      progressions.length === 0
        ? 0
        : progressions[d.boundaryIndex] ?? 0;
    const pageEnd =
      progressions.length === 0
        ? 1
        : progressions[d.boundaryIndex + 1] ?? 1;
    const group = (grouped.get(d.boundaryIndex) ?? []).sort(
      (a, b) => a.start - b.start
    );
    const count = Math.max(group.length, 1);
    const groupIndex = Math.max(
      group.findIndex((g) => g.index === d.index),
      0
    );
    const interval = Math.max(pageEnd - pageStart, 0);
    const progression =
      interval > 0
        ? pageStart + ((groupIndex + 0.5) / count) * interval
        : pageStart;

    const locator = buildLocatorForSentence(
      d.text,
      d.start,
      d.end,
      combinedText,
      segments,
      Math.max(0, Math.min(1, progression)),
      positionEntries
    );

    return {
      index: d.index,
      text: d.text,
      start: d.start,
      end: d.end,
      progression: Math.max(0, Math.min(1, progression)),
      pageStartProgression: Math.max(0, Math.min(1, pageStart)),
      locator,
    };
  });

  return { href, sentences, totalChars };
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export async function getOrBuildSentenceIndex(
  viewRef: RefObject<any> | any,
  href: string,
  splitter: SentenceSplitter
): Promise<SentenceIndex> {
  if (Platform.OS === 'web') {
    throw new Error('Sentence extraction is not supported on web');
  }

  if (!NativeHighlight?.getChapterRawText) {
    throw new Error(
      'Native HighlightModule.getChapterRawText is not available'
    );
  }

  const cleanHref = href?.trim();
  if (!cleanHref) {
    throw new Error('href is required');
  }

  // Check cache
  const cached = cacheGet(cleanHref);
  if (cached) {
    return cached;
  }

  // Fetch raw text from native
  const rawText = await NativeHighlight.getChapterRawText(
    requireReactTag(viewRef),
    cleanHref
  );

  // Build sentence index
  const index = buildSentenceIndex(cleanHref, rawText, splitter);

  // Cache it
  cachePut(cleanHref, index);

  return index;
}

export function getSentenceIndexFromProgressionSync(
  sentenceIndex: SentenceIndex,
  progression: number,
  positionProgressions: number[]
): number {
  const { sentences } = sentenceIndex;
  if (sentences.length === 0) return 0;

  const p = Math.max(0, Math.min(1, progression));

  const bIdx = (() => {
    if (positionProgressions.length === 0) return 0;
    let idx = 0;
    for (let i = 0; i < positionProgressions.length; i++) {
      if (positionProgressions[i] <= p + EPS) {
        idx = i;
      } else {
        break;
      }
    }
    return idx;
  })();

  const pageStart =
    positionProgressions.length === 0
      ? 0
      : positionProgressions[bIdx] ?? 0;
  const pageEnd =
    positionProgressions.length === 0
      ? 1
      : positionProgressions[bIdx + 1] ?? 1;

  const inPage = sentences
    .filter(
      (s) =>
        Math.abs(s.pageStartProgression - pageStart) <= EPS
    )
    .sort((a, b) => a.progression - b.progression);

  if (inPage.length === 0) {
    return sentences[0].index;
  }

  // Exact page boundary => first sentence on that page.
  if (p <= pageStart + EPS) {
    return inPage[0].index;
  }

  // Otherwise floor within the page.
  let resolved = inPage[0].index;
  for (const s of inPage) {
    if (s.progression <= p + EPS && s.progression < pageEnd + EPS) {
      resolved = s.index;
    } else {
      break;
    }
  }
  return resolved;
}
