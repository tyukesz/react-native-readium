import type { Locator } from '@tyukesz/react-native-readium';
import { RNFS } from 'common-app';

export const EPUB_URL = 'https://test.opds.io/assets/moby/file.epub';

export const getEpubPath = () => `${RNFS.DocumentDirectoryPath}/moby-dick.epub`;

/** Name of a local epub file bundled in app assets. Change this to load a different epub. */
export const LOCAL_EPUB_NAME = 'a_balek.epub';
// export const LOCAL_EPUB_NAME = 'moby-dick.epub';
// export const LOCAL_EPUB_NAME = 'ferfi.epub';

export const INITIAL_LOCATION: Locator = {
  // href: '/OPS/main3.xml',
  href: 'main-1.xhtml',
  type: 'application/xhtml+xml',
  locations: {
    progression: 0,
  },
};
