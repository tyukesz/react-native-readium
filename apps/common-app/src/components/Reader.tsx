import React, { useCallback, useEffect, useState, useRef } from 'react';
import {
  StyleSheet,
  View,
  Text,
  Platform,
  DimensionValue,
  Pressable,
} from 'react-native';
import {
  ReadiumView,
  getChapterSentencePage,
  getSentenceIndexFromProgression,
  highlightSentence,
  navigateTo,
  clearHighlight as clearNativeHighlight,
  getVisibleTextRange,
  openPublicationHeadless,
} from '@tyukesz/react-native-readium';
import type {
  Link,
  Locator,
  ReadiumProps,
  PublicationReadyEvent,
} from '@tyukesz/react-native-readium';

import { ReaderButton } from './ReaderButton';
import { PreferencesEditor } from './PreferencesEditor';
import {
  HighlightModal,
  type SentencePreviewItem,
  type VisibleRange,
} from './HighlightModal';
import { useEpubFile } from '../hooks/useEpubFile';
import { useExternalLocation } from '../hooks/useExternalLocation';

export interface ReaderProps {
  /** URL to the EPUB file (used for web or downloading on native) */
  epubUrl: string;
  /** Local file path for the EPUB (used on native platforms after download) */
  epubPath?: string;
  /** Initial location to open the book at */
  initialLocation?: Locator;
  /** Optional external location to navigate to (e.g., from a TOC screen) */
  externalLocation?: Locator | Link;
  /** Optional callback to open a TOC screen */
  onOpenToc?: () => void;
  /** Optional callback when TOC is available */
  onTocChange?: (toc: Link[]) => void;
}

export const Reader: React.FC<ReaderProps> = ({
  epubUrl,
  epubPath,
  initialLocation,
  externalLocation,
  onOpenToc,
  onTocChange,
}) => {
  const { file, isLoading } = useEpubFile({
    epubUrl,
    epubPath,
    initialLocation,
  });
  const { location, setLocation } = useExternalLocation(externalLocation);
  const [preferences, setPreferences] = useState<ReadiumProps['preferences']>({
    theme: 'dark',
  });
  const [isHighlightModalVisible, setIsHighlightModalVisible] = useState(false);
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
  const ref = useRef<any>(undefined);
  const isNative = Platform.OS !== 'web';

  const openHighlightModal = () => {
    // Default chapter to current chapter if available.
    const currentHref =
      location && 'href' in location ? (location.href as string) : '';
    setHighlightHref((prev) => prev || currentHref);
    setSentenceCount(null);
    setSentencePreview(null);
    setIsHighlightModalVisible(true);
  };

  const applyHighlight = async () => {
    const href = highlightHref.trim();
    if (!href) return;

    const idx = Number(sentenceIndexText);
    if (!Number.isInteger(idx) || idx < 0) return;

    highlightSentence(ref, {
      href,
      sentenceIndex: idx,
      style: {
        tint: '#f4090d',
        isActive: false,
      },
    });

    // Highlighting no longer navigates; navigate explicitly.
    if (isNative) {
      try {
        const page = await getChapterSentencePage(ref, {
          href,
          offset: idx,
          limit: 1,
        });
        const item = page.items?.[0];
        if (!item) {
          throw new Error('Failed to resolve sentence page item');
        }

        if (item.locator) {
          console.log('navigateTo locator', item);
          await navigateTo(ref, item.locator);
        }
      } catch (e) {
        console.log('navigateTo failed', e);
      }
    }
    setIsHighlightModalVisible(false);
  };

  const clearHighlightAction = () => {
    clearNativeHighlight(ref);
    setIsHighlightModalVisible(false);
  };

  const loadSentencesCount = useCallback(async () => {
    const href = highlightHref.trim();
    if (!href) return;
    if (!isNative) {
      setSentenceCount(null);
      return;
    }

    try {
      setIsLoadingSentences(true);
      const page = await getChapterSentencePage(ref, {
        href,
        offset: 0,
        limit: 0,
      });
      setSentenceCount(page.total);
    } catch (e) {
      console.log('getChapterSentences failed', e);
      setSentenceCount(null);
    } finally {
      setIsLoadingSentences(false);
    }
  }, [highlightHref, isNative]);

  const loadSentencePreview = useCallback(async () => {
    if (!isNative) return;

    const result = await openPublicationHeadless({ url: file!.url });
    console.log(result);

    try {
      setIsLoadingPreview(true);
      const res = await getVisibleTextRange(ref, {
        includeText: true,
        source: 'viewport',
      });
      console.log(res);
      setVisibleRange(res);
    } catch (e) {
      console.log('loadSentencePreview failed', e);
      setSentencePreview(null);
      setVisibleRange(null);
    } finally {
      setIsLoadingPreview(false);
    }
  }, [isNative, file]);

  const jumpToProgression = useCallback(async () => {
    const href = highlightHref.trim();
    if (!href) return;
    const p = Number(progressionText);
    if (!Number.isFinite(p)) return;

    if (!isNative) return;
    try {
      const idx = await getSentenceIndexFromProgression(ref, {
        href,
        progression: p,
      });
      console.log({ href, p, idx });
      setSentenceIndexText(String(idx));
      highlightSentence(ref, { href, sentenceIndex: idx });

      const page = await getChapterSentencePage(ref, {
        href,
        offset: idx,
        limit: 1,
      });
      const item = page.items?.[0];
      if (item?.locator) {
        await navigateTo(ref, item.locator);
      }
    } catch (e) {
      console.log('getSentenceIndexFromProgression failed', e);
    }
  }, [highlightHref, progressionText, isNative]);

  useEffect(() => {
    if (!isHighlightModalVisible) return;
    setSentencePreview(null);
  }, [highlightHref, isHighlightModalVisible]);

  if (file) {
    return (
      <View style={styles.container}>
        <View style={styles.controls}>
          <View style={styles.button}>
            {onOpenToc ? (
              <Pressable onPress={onOpenToc} style={styles.actionButton}>
                <Text style={styles.actionButtonText}>Table of Contents</Text>
              </Pressable>
            ) : null}
          </View>
          <View style={styles.button}>
            <PreferencesEditor
              preferences={preferences}
              onChange={setPreferences}
            />
          </View>
          <View style={styles.button}>
            <Pressable onPress={openHighlightModal} style={styles.actionButton}>
              <Text style={styles.actionButtonText}>Highlight sentence</Text>
            </Pressable>
          </View>
        </View>

        <View style={styles.reader}>
          {!isNative ? (
            <ReaderButton
              name="chevron-left"
              style={styles.webNavButton}
              onPress={() => ref.current?.prevPage()}
            />
          ) : null}
          <View style={styles.readiumContainer}>
            <ReadiumView
              ref={ref}
              file={file}
              location={location}
              preferences={preferences}
              hidePageNumbers={true}
              onLocationChange={(locator: Locator) => {
                console.log('onLocationChange', locator);
                setLocation(locator);
              }}
              onPublicationReady={(event: PublicationReadyEvent) => {
                console.log('onPublicationReady', event);
                if (onTocChange) {
                  onTocChange(event.tableOfContents || []);
                }
              }}
              enableTapNavigation={false}
            />
          </View>
          {!isNative ? (
            <ReaderButton
              name="chevron-right"
              style={styles.webNavButton}
              onPress={() => ref.current?.nextPage()}
            />
          ) : null}
        </View>

        <HighlightModal
          visible={isHighlightModalVisible}
          isNative={isNative}
          highlightHref={highlightHref}
          sentenceIndexText={sentenceIndexText}
          progressionText={progressionText}
          sentenceCount={sentenceCount}
          isLoadingSentences={isLoadingSentences}
          isLoadingPreview={isLoadingPreview}
          visibleRange={visibleRange}
          sentencePreview={sentencePreview}
          onClose={() => setIsHighlightModalVisible(false)}
          onApply={applyHighlight}
          onClear={clearHighlightAction}
          onChangeHighlightHref={setHighlightHref}
          onChangeSentenceIndexText={setSentenceIndexText}
          onChangeProgressionText={setProgressionText}
          onUseCurrentChapter={() => {
            const currentHref =
              location && 'href' in location ? (location.href as string) : '';
            if (currentHref) setHighlightHref(currentHref);
          }}
          onLoadSentences={loadSentencesCount}
          onLoadPreview={loadSentencePreview}
          onJumpToProgression={jumpToProgression}
        />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text>{isLoading ? 'downloading file' : 'file not available'}</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    height: (Platform.OS === 'web' ? '100vh' : '100%') as DimensionValue,
  },
  reader: {
    flexDirection: 'row',
    width: '100%',
    height: '90%',
  },
  readiumContainer: {
    width: Platform.OS === 'web' ? '80%' : '100%',
    height: '100%',
  },
  webNavButton: {
    width: '10%',
  },
  controls: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
  },
  button: {
    margin: 10,
  },
  actionButton: {
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
});
