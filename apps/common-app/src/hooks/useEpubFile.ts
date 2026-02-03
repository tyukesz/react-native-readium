import { useEffect, useState } from 'react';
import type { File, Locator } from '@tyukesz/react-native-readium';

import RNFS from '../utils/RNFS';

interface UseEpubFileParams {
  epubUrl: string;
  epubPath?: string;
  initialLocation?: Locator;
}

export const useEpubFile = ({
  epubUrl,
  epubPath,
  initialLocation,
}: UseEpubFileParams) => {
  const [file, setFile] = useState<File>();
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let isMounted = true;

    async function run() {
      setIsLoading(true);
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
  }, [epubUrl, epubPath, initialLocation]);

  return { file, isLoading };
};
