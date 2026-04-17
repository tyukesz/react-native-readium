import React, { useEffect, useMemo, useRef, useState } from 'react';
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
import { HighlightModal } from './HighlightModal';
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
  /** Example mode: only first two readingOrder chapters are allowed */
  limitToFirstTwoChapters?: boolean;
  /** Name of a local epub bundled in app assets (e.g. "ferfi.epub") */
  localEpubName?: string;
}

export const Reader: React.FC<ReaderProps> = ({
  epubUrl,
  epubPath,
  initialLocation,
  externalLocation,
  onOpenToc,
  onTocChange,
  limitToFirstTwoChapters = false,
  localEpubName,
}) => {
  const { file, isLoading } = useEpubFile({
    epubUrl,
    epubPath,
    initialLocation,
    localEpubName,
  });
  const { location: externalNav } = useExternalLocation(externalLocation);
  const [currentLocation, setCurrentLocation] = useState<Locator>();
  const [preferences, setPreferences] = useState<ReadiumProps['preferences']>({
    theme: 'sepia',
  });
  const [isHighlightModalVisible, setIsHighlightModalVisible] = useState(false);
  // const [allowedHrefs, setAllowedHrefs] = useState<string[] | undefined>(
  //   undefined
  // );
  // const [isLoadingAllowedHrefs, setIsLoadingAllowedHrefs] =
  //   useState<boolean>(false);
  const ref = useRef<any>(undefined);
  const isNative = Platform.OS !== 'web';

  // useEffect(() => {
  //   let isCancelled = false;

  //   async function resolveAllowedHrefs() {
  //     if (!limitToFirstTwoChapters) {
  //       setAllowedHrefs(undefined);
  //       setIsLoadingAllowedHrefs(false);
  //       return;
  //     }

  //     if (!isNative || !file?.url) {
  //       setAllowedHrefs(undefined);
  //       setIsLoadingAllowedHrefs(false);
  //       return;
  //     }

  //     setIsLoadingAllowedHrefs(true);
  //     // Keep access restricted while loading the headless index.
  //     setAllowedHrefs([]);

  //     try {
  //       const index = await openPublicationHeadless({
  //         url: file.url,
  //         id: `first-two-${file.url}`,
  //       });

  //       const readingOrderHrefs = (index.readingOrder || [])
  //         .map((item) => item.href)
  //         .filter((href): href is string => !!href)
  //         .slice(0, 2);

  //       const fallbackPositionHrefs = Array.from(
  //         new Set(
  //           (index.positions || [])
  //             .map((position) => position.href)
  //             .filter((href): href is string => !!href)
  //         )
  //       ).slice(0, 2);

  //       const resolved =
  //         readingOrderHrefs.length > 0
  //           ? readingOrderHrefs
  //           : fallbackPositionHrefs;

  //       if (!isCancelled) {
  //         setAllowedHrefs(resolved);
  //         console.log('Allowed hrefs (first two chapters):', resolved);
  //       }
  //     } catch (error) {
  //       if (!isCancelled) {
  //         console.log(
  //           'Failed to resolve allowed hrefs from headless index',
  //           error
  //         );
  //         setAllowedHrefs([]);
  //       }
  //     } finally {
  //       if (!isCancelled) {
  //         setIsLoadingAllowedHrefs(false);
  //       }
  //     }
  //   }

  //   resolveAllowedHrefs();

  //   return () => {
  //     isCancelled = true;
  //   };
  // }, [file?.url, isNative, limitToFirstTwoChapters]);

  //   const paywallHTML = useMemo(() => {
  //     const message = isLoadingAllowedHrefs
  //       ? 'Resolving the first two allowed chapters.'
  //       : 'Only the first two chapters are enabled in test mode.';

  //     return `<?xml version="1.0" encoding="utf-8"?>
  // <!DOCTYPE html>
  // <html xmlns="http://www.w3.org/1999/xhtml">
  //   <head>
  //     <meta charset="utf-8" />
  //     <meta name="viewport" content="width=device-width, initial-scale=1" />
  //     <title>Subscription required</title>
  //     <style>
  //       :root { color-scheme: light dark; }
  //       html, body {
  //         margin: 0;
  //         min-height: 100%;
  //         background: #101114;
  //         color: #f6f7fb;
  //         font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  //       }
  //       body {
  //         display: flex;
  //         align-items: center;
  //         justify-content: center;
  //         padding: 2rem;
  //         box-sizing: border-box;
  //       }
  //       main {
  //         max-width: 30rem;
  //         text-align: center;
  //       }
  //       h1 {
  //         margin: 0 0 0.75rem;
  //         font-size: 2rem;
  //         line-height: 1.05;
  //       }
  //       p {
  //         margin: 0;
  //         font-size: 1rem;
  //         line-height: 1.6;
  //         opacity: 0.84;
  //       }
  //     </style>
  //   </head>
  //   <body>
  //     <main>
  //       <h1>Subscription required</h1>
  //       <p>${message}</p>
  //     </main>
  //   </body>
  // </html>`;
  //   }, [isLoadingAllowedHrefs]);

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
            <Pressable
              onPress={() => setIsHighlightModalVisible(true)}
              style={styles.actionButton}
            >
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
              location={externalNav}
              disableTextSelection
              preferences={preferences}
              // allowedHrefs={limitToFirstTwoChapters ? allowedHrefs : undefined}
              // paywallHTML={limitToFirstTwoChapters ? paywallHTML : undefined}
              hidePageNumbers={true}
              onLocationChange={(locator: Locator) => {
                console.log('onLocationChange', locator);
                setCurrentLocation(locator);
              }}
              onRestrictedNavigation={(href: string) => {
                console.log('restricted navigation', href);
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
          onClose={() => setIsHighlightModalVisible(false)}
          readerRef={ref}
          location={currentLocation}
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
