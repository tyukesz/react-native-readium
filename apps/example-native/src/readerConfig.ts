import type { Locator } from '@tyukesz/react-native-readium';
import { RNFS } from 'common-app';

export const EPUB_URL = 'https://test.opds.io/assets/moby/file.epub';

export const getEpubPath = () => `${RNFS.DocumentDirectoryPath}/moby-dick.epub`;

export const INITIAL_LOCATION: Locator = {
  // href: '/OPS/main3.xml',
  href: 'main-1.xhtml',
  type: 'application/xhtml+xml',
  locations: {
    progression: 0,
  },
};
