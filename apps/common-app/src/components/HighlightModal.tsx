import React, { useCallback, useEffect, useState } from 'react';
import {
  Modal,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  clearHighlight as clearNativeHighlight,
  getChapterSentencePage,
  getSentenceIndexFromProgression,
  getVisibleTextRange,
  highlightLocator,
  navigateTo,
} from '@tyukesz/react-native-readium';
import type {
  Link,
  Locator,
  SentenceCleaner,
  SentenceSplitter,
} from '@tyukesz/react-native-readium';

export type VisibleRange = {
  href: string;
  start: number;
  end: number;
  totalChars: number;
};

export type SentencePreviewItem = {
  index: number;
  text: string;
  progression?: number;
};

// Default sentence splitter — returns raw substrings (no character transformation).
// 1. Split on any newline(s) — each line/paragraph becomes a separate chunk
// 2. Within each chunk, split into sentences using Intl.Segmenter or regex fallback
const defaultSplitter: SentenceSplitter = (rawText: string): string[] => {
  const chunks = rawText
    .split(/\n+/)
    .map((c) => c.trim())
    .filter((c) => c.length > 0);

  const results: string[] = [];

  for (const chunk of chunks) {
    if (typeof Intl !== 'undefined' && 'Segmenter' in Intl) {
      const segmenter = new (Intl as any).Segmenter('en', {
        granularity: 'sentence',
      });
      for (const seg of segmenter.segment(chunk)) {
        const trimmed = seg.segment.trim();
        if (trimmed.length > 0) results.push(trimmed);
      }
    } else {
      for (const s of chunk.split(/(?<=[.!?])\s+(?=[A-Z])/)) {
        const trimmed = s.trim();
        if (trimmed.length > 0) results.push(trimmed);
      }
    }
  }

  return results;
};

// Default cleaner — normalizes whitespace (collapse runs, trim).
const defaultCleaner: SentenceCleaner = (text: string): string => {
  return text.replace(/\s+/g, ' ').trim();
};

interface HighlightModalProps {
  visible: boolean;
  onClose: () => void;
  readerRef: React.RefObject<any>;
  location?: Locator | Link;
  splitter?: SentenceSplitter;
  cleaner?: SentenceCleaner;
}

export const HighlightModal: React.FC<HighlightModalProps> = ({
  visible,
  onClose,
  readerRef,
  location,
  splitter = defaultSplitter,
  cleaner = defaultCleaner,
}) => {
  const isNative = Platform.OS !== 'web';
  const [highlightHref, setHighlightHref] = useState<string>('');
  const [sentenceIndexText, setSentenceIndexText] = useState<string>('0');
  const [sentenceCount, setSentenceCount] = useState<number | null>(null);
  const [isLoadingSentences, setIsLoadingSentences] = useState<boolean>(false);
  const [sentencePreview, setSentencePreview] = useState<
    SentencePreviewItem[] | null
  >(null);
  const [isLoadingPreview, setIsLoadingPreview] = useState<boolean>(false);
  const [visibleRange, setVisibleRange] = useState<VisibleRange | null>(null);
  const [progressionText, setProgressionText] = useState<string>('0');

  const useCurrentChapter = useCallback(() => {
    const currentHref =
      location && 'href' in location ? (location.href as string) : '';
    if (currentHref) {
      setHighlightHref(currentHref);
    }
  }, [location]);

  const applyHighlightLocator = useCallback(async () => {
    const href = highlightHref.trim();
    if (!href) return;

    const idx = Number(sentenceIndexText);
    if (!Number.isInteger(idx) || idx < 0) return;

    if (!isNative) return;

    try {
      const page = await getChapterSentencePage(readerRef, {
        href,
        splitter,
        cleaner,
      });
      const item = page.items?.[idx];
      if (!item?.locator) {
        throw new Error('Failed to resolve locator for sentence');
      }

      console.log('highlightLocator', item.locator);

      highlightLocator(readerRef, item.locator, {
        tint: '#0953f4',
        isActive: false,
      });

      await navigateTo(readerRef, item.locator);
    } catch (e) {
      console.log('highlightLocator failed', e);
    }

    onClose();
  }, [
    highlightHref,
    sentenceIndexText,
    isNative,
    onClose,
    readerRef,
    splitter,
    cleaner,
  ]);

  const clearHighlightAction = useCallback(() => {
    clearNativeHighlight(readerRef);
    onClose();
  }, [readerRef, onClose]);

  const loadSentencesCount = useCallback(async () => {
    const href = highlightHref.trim();
    if (!href) return;
    if (!isNative) {
      setSentenceCount(null);
      return;
    }

    try {
      setIsLoadingSentences(true);
      const page = await getChapterSentencePage(readerRef, {
        href,
        splitter,
        cleaner,
      });
      console.log('loadSentencesCount', page);
      setSentenceCount(page.total);
    } catch (e) {
      console.log('getChapterSentences failed', e);
      setSentenceCount(null);
    } finally {
      setIsLoadingSentences(false);
    }
  }, [highlightHref, isNative, readerRef, splitter, cleaner]);

  const loadSentencePreview = useCallback(async () => {
    if (!isNative) return;

    try {
      setIsLoadingPreview(true);
      const res = await getVisibleTextRange(readerRef, {
        includeText: true,
        source: 'viewport',
      });
      console.log({ start: res?.start, end: res?.end, text: res?.text });
      setVisibleRange(res);
    } catch (e) {
      console.log('loadSentencePreview failed', e);
      setSentencePreview(null);
      setVisibleRange(null);
    } finally {
      setIsLoadingPreview(false);
    }
  }, [isNative, readerRef]);

  const jumpToProgression = useCallback(async () => {
    const href = highlightHref.trim();
    if (!href) return;
    const p = Number(progressionText);
    if (!Number.isFinite(p)) return;

    if (!isNative) return;
    try {
      const idx = await getSentenceIndexFromProgression(readerRef, {
        href,
        progression: p,
        splitter,
        cleaner,
      });
      console.log({ href, p, idx });
      setSentenceIndexText(String(idx));

      const page = await getChapterSentencePage(readerRef, {
        href,
        offset: idx,
        limit: 1,
        splitter,
        cleaner,
      });
      const item = page.items?.[0];
      if (item?.locator) {
        highlightLocator(readerRef, item.locator);
        await navigateTo(readerRef, item.locator);
      }
    } catch (e) {
      console.log('getSentenceIndexFromProgression failed', e);
    }
  }, [highlightHref, progressionText, isNative, readerRef, splitter, cleaner]);

  useEffect(() => {
    if (!visible) {
      return;
    }

    const currentHref =
      location && 'href' in location ? (location.href as string) : '';
    setHighlightHref((prev) => prev || currentHref);
    setSentenceCount(null);
    setSentencePreview(null);
  }, [visible, location]);

  useEffect(() => {
    if (!visible) return;
    setSentencePreview(null);
  }, [highlightHref, visible]);

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={onClose}
    >
      <View style={styles.modalBackdrop}>
        <View style={styles.modalCard}>
          <Text style={styles.modalTitle}>Highlight sentence</Text>

          <Text style={styles.modalLabel}>Chapter href</Text>
          <TextInput
            value={highlightHref}
            onChangeText={setHighlightHref}
            placeholder="e.g. /OPS/chapter-1.xhtml"
            placeholderTextColor="#777"
            autoCapitalize="none"
            autoCorrect={false}
            style={styles.modalInput}
          />

          <View style={styles.modalRowLeft}>
            <Pressable onPress={useCurrentChapter} style={styles.modalChip}>
              <Text style={styles.modalChipText}>Use current chapter</Text>
            </Pressable>

            <Pressable
              onPress={loadSentencesCount}
              style={styles.modalChip}
              disabled={isLoadingSentences || !isNative}
            >
              <Text style={styles.modalChipText}>
                {isLoadingSentences
                  ? 'Loading…'
                  : !isNative
                  ? 'Sentences (Native only)'
                  : `Sentences: ${sentenceCount ?? '—'}`}
              </Text>
            </Pressable>

            <Pressable
              onPress={loadSentencePreview}
              style={styles.modalChip}
              disabled={isLoadingPreview || !isNative}
            >
              <Text style={styles.modalChipText}>
                {!isNative
                  ? 'Visible range (Native only)'
                  : isLoadingPreview
                  ? 'Loading…'
                  : 'Get visible range'}
              </Text>
            </Pressable>
          </View>

          <Text style={styles.modalHint}>
            Total sentences: {sentenceCount ?? '—'}
          </Text>

          {visibleRange ? (
            <Text style={styles.modalHint}>
              Visible range: {visibleRange.start}..{visibleRange.end} /{' '}
              {visibleRange.totalChars} ({visibleRange.href})
            </Text>
          ) : null}

          {sentencePreview?.length ? (
            <Text style={styles.modalPreview}>
              {sentencePreview.map((s) => `${s.index}: ${s.text}`).join('\n\n')}
            </Text>
          ) : null}

          <Text style={styles.modalLabel}>Sentence index (0-based)</Text>
          <TextInput
            value={sentenceIndexText}
            onChangeText={setSentenceIndexText}
            keyboardType="number-pad"
            style={styles.modalInput}
          />

          <Text style={styles.modalLabel}>Start from progression (0..1)</Text>
          <TextInput
            value={progressionText}
            onChangeText={setProgressionText}
            keyboardType="decimal-pad"
            style={styles.modalInput}
          />

          <View style={styles.modalRowLeft}>
            <Pressable
              onPress={jumpToProgression}
              style={styles.modalChip}
              disabled={!isNative}
            >
              <Text style={styles.modalChipText}>Jump to %</Text>
            </Pressable>
          </View>

          <View style={styles.modalRow}>
            <Pressable
              onPress={clearHighlightAction}
              style={styles.modalButton}
            >
              <Text style={styles.actionButtonText}>Clear</Text>
            </Pressable>
            <Pressable onPress={onClose} style={styles.modalButton}>
              <Text style={styles.actionButtonText}>Cancel</Text>
            </Pressable>
            <Pressable
              onPress={applyHighlightLocator}
              style={[styles.modalButton, styles.modalButtonPrimary]}
            >
              <Text style={styles.modalPrimaryButtonText}>
                Highlight (Locator)
              </Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.6)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 16,
  },
  modalCard: {
    width: '100%',
    maxWidth: 520,
    borderRadius: 12,
    backgroundColor: '#1f1f1f',
    borderWidth: 1,
    borderColor: '#333',
    padding: 14,
  },
  modalTitle: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 10,
  },
  modalLabel: {
    color: '#bbb',
    marginTop: 10,
    marginBottom: 6,
  },
  modalHint: {
    color: '#8a8a8a',
    marginTop: 8,
    fontSize: 12,
  },
  modalPreview: {
    color: '#ddd',
    marginTop: 10,
    fontSize: 12,
    lineHeight: 16,
  },
  modalInput: {
    color: '#fff',
    backgroundColor: '#151515',
    borderWidth: 1,
    borderColor: '#2a2a2a',
    borderRadius: 10,
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  modalRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    flexWrap: 'wrap',
    gap: 10,
    marginTop: 14,
  },
  modalRowLeft: {
    flexDirection: 'row',
    justifyContent: 'flex-start',
    marginTop: 10,
  },
  modalChip: {
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: 999,
    backgroundColor: '#2b2b2b',
    borderWidth: 1,
    borderColor: '#444',
  },
  modalChipText: {
    color: '#fff',
    fontSize: 12,
  },
  modalButton: {
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 8,
    backgroundColor: '#2b2b2b',
    borderWidth: 1,
    borderColor: '#444',
  },
  actionButtonText: {
    color: '#fff',
  },
  modalButtonPrimary: {
    backgroundColor: '#ffd400',
    borderColor: '#ffd400',
  },
  modalPrimaryButtonText: {
    color: '#000',
    fontSize: 14,
    fontWeight: '600',
  },
});
