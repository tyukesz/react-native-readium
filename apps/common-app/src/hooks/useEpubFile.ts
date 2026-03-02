import { useEffect, useState } from 'react';
import type { File, Locator } from '@tyukesz/react-native-readium';
import { Platform } from 'react-native';

import RNFS from '../utils/RNFS';

interface UseEpubFileParams {
  epubUrl: string;
  epubPath?: string;
  initialLocation?: Locator;
  useFerfiEpub?: boolean;
}

export const useEpubFile = ({
  epubUrl,
  epubPath,
  initialLocation,
  useFerfiEpub = false,
}: UseEpubFileParams) => {
  const [file, setFile] = useState<File>();
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let isMounted = true;

    async function isValidLocalFile(path: string) {
      const exists = await RNFS.exists(path);
      if (!exists) return false;
      try {
        const stat = await RNFS.stat(path);
        // Defensive: a previous failed copy can leave a 0-byte file behind.
        return Number(stat?.size ?? 0) > 0;
      } catch {
        return false;
      }
    }

    async function run() {
      setIsLoading(true);
      let url = epubUrl;

      const remoteLocalPath =
        epubPath || `${RNFS.DocumentDirectoryPath}/${epubUrl.split('/').pop()}`;

      const bundledLocalPath = `${RNFS.DocumentDirectoryPath}/ferfi.epub`;

      if (useFerfiEpub) {
        const valid = await isValidLocalFile(bundledLocalPath);
        if (!valid) {
          // Remove any stale/empty file before copying.
          const exists = await RNFS.exists(bundledLocalPath);
          if (exists) {
            try {
              await RNFS.unlink(bundledLocalPath);
            } catch {
              // ignore
            }
          }

          if (Platform.OS === 'android') {
            const copyFileAssets = (RNFS as any).copyFileAssets as
              | ((
                  assetFilePath: string,
                  destinationPath: string
                ) => Promise<void>)
              | undefined;
            if (typeof copyFileAssets !== 'function') {
              throw new Error(
                'RNFS.copyFileAssets is not available on Android'
              );
            }
            await copyFileAssets('ferfi.epub', bundledLocalPath);
          } else if (Platform.OS === 'ios') {
            const sourcePath = `${RNFS.MainBundlePath}/ferfi.epub`;
            const sourceExists = await RNFS.exists(sourcePath);
            if (!sourceExists) {
              throw new Error(
                `Bundled ferfi.epub is missing in iOS app bundle (expected at: ${sourcePath}). ` +
                  'Add ferfi.epub to the Xcode target “Copy Bundle Resources”.'
              );
            }
            await RNFS.copyFile(sourcePath, bundledLocalPath);
          } else {
            throw new Error(
              'Bundled ferfi.epub is not supported on this platform'
            );
          }
        }

        const afterCopyValid = await isValidLocalFile(bundledLocalPath);
        if (!afterCopyValid) {
          throw new Error(
            `Failed to provision local ferfi.epub at: ${bundledLocalPath}`
          );
        }

        url = bundledLocalPath;
      } else {
        const exists = await RNFS.exists(remoteLocalPath);
        if (!exists) {
          console.log(`Downloading file: '${epubUrl}'`);
          const { promise } = RNFS.downloadFile({
            fromUrl: epubUrl,
            toFile: remoteLocalPath,
            background: true,
            discretionary: true,
          });

          await promise;
        } else {
          console.log('File already exists. Skipping download.');
        }

        url = remoteLocalPath;
      }

      if (!isMounted) return;
      setFile({
        url,
        initialLocation,
      });
      setIsLoading(false);
    }

    run();

    return () => {
      isMounted = false;
    };
  }, [epubUrl, epubPath, initialLocation, useFerfiEpub]);

  return { file, isLoading };
};
