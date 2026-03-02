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

    async function run() {
      setIsLoading(true);
      let url = epubUrl;

      const remoteLocalPath =
        epubPath || `${RNFS.DocumentDirectoryPath}/${epubUrl.split('/').pop()}`;

      const bundledLocalPath = `${RNFS.DocumentDirectoryPath}/ferfi.epub`;

      if (useFerfiEpub) {
        const exists = await RNFS.exists(bundledLocalPath);
        if (!exists) {
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
            await RNFS.copyFile(sourcePath, bundledLocalPath);
          } else {
            throw new Error('Bundled ferfi.epub is not supported on this platform');
          }
        } else {
          console.log('Local ferfi.epub already exists. Skipping copy.');
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
