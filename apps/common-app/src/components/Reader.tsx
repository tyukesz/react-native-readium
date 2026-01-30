import React, { useCallback, useEffect, useState, useRef } from 'react';
import {
  StyleSheet,
  View,
  Text,
  Platform,
  DimensionValue,
  Modal,
  Pressable,
  TextInput,
} from 'react-native';
import {
  ReadiumView,
  getChapterSentencePage,
  getSentenceIndexFromProgression,
  highlightSentence,
  clearHighlight as clearNativeHighlight,
} from 'react-native-readium';
import type {
  Link,
  Locator,
  File,
  ReadiumProps,
  PublicationReadyEvent,
} from 'react-native-readium';

import RNFS from '../utils/RNFS';
import { ReaderButton } from './ReaderButton';
import { PreferencesEditor } from './PreferencesEditor';

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
  type PreviewItem = { index: number; text: string; progression?: number };

  const [toc, setToc] = useState<Link[] | null>([]);
  const [file, setFile] = useState<File>();
  const [location, setLocation] = useState<Locator | Link>();
  const [preferences, setPreferences] = useState<ReadiumProps['preferences']>({
    theme: 'dark',
  });
  const [isHighlightModalVisible, setIsHighlightModalVisible] = useState(false);
  const [highlightHref, setHighlightHref] = useState<string>('');
  const [sentenceIndexText, setSentenceIndexText] = useState<string>('0');
  const [sentenceCount, setSentenceCount] = useState<number | null>(null);
  const [isLoadingSentences, setIsLoadingSentences] = useState<boolean>(false);
  const [sentencePreview, setSentencePreview] = useState<PreviewItem[] | null>(
    null
  );
  const [isLoadingPreview, setIsLoadingPreview] = useState<boolean>(false);
  const [progressionText, setProgressionText] = useState<string>('0');
  const ref = useRef<any>(undefined);
  const lastExternalLocation = useRef<Locator | Link | undefined>(undefined);

  const openHighlightModal = () => {
    // Default chapter to current chapter if available.
    const currentHref =
      location && 'href' in location ? (location.href as string) : '';
    setHighlightHref((prev) => prev || currentHref);
    setSentenceCount(null);
    setSentencePreview(null);
    setIsHighlightModalVisible(true);
  };

  const applyHighlight = () => {
    const href = highlightHref.trim();
    if (!href) return;

    const idx = Number(sentenceIndexText);
    if (!Number.isInteger(idx) || idx < 0) return;

    highlightSentence(ref, {
      href,
      sentenceIndex: idx,
      style: {
        tint: '#34f409',
        isActive: false,
      },
    });
    setIsHighlightModalVisible(false);
  };

  const clearHighlightAction = () => {
    clearNativeHighlight(ref);
    setIsHighlightModalVisible(false);
  };

  const loadSentencesCount = useCallback(async () => {
    const href = highlightHref.trim();
    if (!href) return;
    if (Platform.OS === 'web') {
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
  }, [highlightHref]);

  const loadSentencePreview = useCallback(async () => {
    const href = highlightHref.trim();
    if (!href) return;
    if (Platform.OS === 'web') return;

    try {
      setIsLoadingPreview(true);
      const page = await getChapterSentencePage(ref, {
        href,
        offset: 35,
        limit: 3,
      });
      console.log({ page });
      setSentencePreview(page.items);
    } catch (e) {
      console.log('loadSentencePreview failed', e);
      setSentencePreview(null);
    } finally {
      setIsLoadingPreview(false);
    }
  }, [highlightHref]);

  const jumpToProgression = useCallback(async () => {
    const href = highlightHref.trim();
    if (!href) return;
    const p = Number(progressionText);
    if (!Number.isFinite(p)) return;

    if (Platform.OS === 'web') return;
    try {
      const idx = await getSentenceIndexFromProgression(ref, {
        href,
        progression: p,
      });
      console.log({ href, p, idx });
      setSentenceIndexText(String(idx));
      highlightSentence(ref, { href, sentenceIndex: idx });
    } catch (e) {
      console.log('getSentenceIndexFromProgression failed', e);
    }
  }, [highlightHref, progressionText]);

  useEffect(() => {
    if (!isHighlightModalVisible) return;
    setSentencePreview(null);
  }, [highlightHref, isHighlightModalVisible]);

  useEffect(() => {
    if (!isHighlightModalVisible) return;
    if (!highlightHref.trim()) return;

    // Avoid hammering native while the user is typing.
    const t = setTimeout(() => {
      loadSentencesCount();
    }, 250);

    return () => clearTimeout(t);
  }, [isHighlightModalVisible, highlightHref, loadSentencesCount]);

  useEffect(() => {
    async function run() {
      let url = epubUrl;

      const localPath =
        epubPath || `${RNFS.DocumentDirectoryPath}/${epubUrl.split('/').pop()}`;

      const exists = await RNFS.exists(localPath);
      if (!exists) {
        console.log(`Downloading file: '${epubUrl}'`);
        const { promise } = RNFS.downloadFile({
          fromUrl: epubUrl,
          toFile: localPath,
          background: true,
          discretionary: true,
        });

        await promise;
      } else {
        console.log('File already exists. Skipping download.');
      }

      url = localPath;

      setFile({
        url,
        initialLocation,
      });
    }

    run();
  }, [epubUrl, epubPath, initialLocation]);

  useEffect(() => {
    if (!externalLocation) return;
    if (lastExternalLocation.current === externalLocation) return;
    lastExternalLocation.current = externalLocation;
    setLocation(externalLocation);
  }, [externalLocation]);

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
          {Platform.OS === 'web' ? (
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
              onLocationChange={(locator: Locator) => {
                console.log('onLocationChange', locator);
                setLocation(locator);
              }}
              onPublicationReady={(event: PublicationReadyEvent) => {
                console.log('onPublicationReady', event);
                // Set the TOC from the new event
                setToc(event.tableOfContents);
                if (onTocChange) {
                  onTocChange(event.tableOfContents || []);
                }
              }}
            />
          </View>
          {Platform.OS === 'web' ? (
            <ReaderButton
              name="chevron-right"
              style={styles.webNavButton}
              onPress={() => ref.current?.nextPage()}
            />
          ) : null}
        </View>

        <Modal
          visible={isHighlightModalVisible}
          transparent
          animationType="fade"
          onRequestClose={() => setIsHighlightModalVisible(false)}
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
                <Pressable
                  onPress={() => {
                    const currentHref =
                      location && 'href' in location
                        ? (location.href as string)
                        : '';
                    if (currentHref) setHighlightHref(currentHref);
                  }}
                  style={styles.modalChip}
                >
                  <Text style={styles.modalChipText}>Use current chapter</Text>
                </Pressable>

                <Pressable
                  onPress={loadSentencesCount}
                  style={styles.modalChip}
                  disabled={isLoadingSentences || Platform.OS === 'web'}
                >
                  <Text style={styles.modalChipText}>
                    {isLoadingSentences
                      ? 'Loading…'
                      : Platform.OS === 'web'
                      ? 'Sentences (Native only)'
                      : `Sentences: ${sentenceCount ?? '—'}`}
                  </Text>
                </Pressable>

                <Pressable
                  onPress={loadSentencePreview}
                  style={styles.modalChip}
                  disabled={isLoadingPreview || Platform.OS === 'web'}
                >
                  <Text style={styles.modalChipText}>
                    {Platform.OS === 'web'
                      ? 'Preview (Native only)'
                      : isLoadingPreview
                      ? 'Previewing…'
                      : 'Preview first 3'}
                  </Text>
                </Pressable>
              </View>

              <Text style={styles.modalHint}>
                Total sentences: {sentenceCount ?? '—'}
              </Text>

              {sentencePreview?.length ? (
                <Text style={styles.modalPreview}>
                  {sentencePreview
                    .map((s) => `${s.index}: ${s.text}`)
                    .join('\n\n')}
                </Text>
              ) : null}

              <Text style={styles.modalLabel}>Sentence index (0-based)</Text>
              <TextInput
                value={sentenceIndexText}
                onChangeText={setSentenceIndexText}
                keyboardType="number-pad"
                style={styles.modalInput}
              />

              <Text style={styles.modalLabel}>
                Start from progression (0..1)
              </Text>
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
                  disabled={Platform.OS === 'web'}
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
                <Pressable
                  onPress={() => setIsHighlightModalVisible(false)}
                  style={styles.modalButton}
                >
                  <Text style={styles.actionButtonText}>Cancel</Text>
                </Pressable>
                <Pressable
                  onPress={applyHighlight}
                  style={[styles.modalButton, styles.modalButtonPrimary]}
                >
                  <Text style={styles.modalPrimaryButtonText}>Highlight</Text>
                </Pressable>
              </View>
            </View>
          </View>
        </Modal>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text>downloading file</Text>
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
