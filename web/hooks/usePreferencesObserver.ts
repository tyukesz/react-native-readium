import { useDeepCompareEffect } from 'use-deep-compare';

import { EpubNavigator, EpubPreferences } from '@readium/navigator';
import { mapPreferencesToNavigator } from '../../src/utils';

export const usePreferencesObserver = (
  navigator?: EpubNavigator | null,
  preferences?: any
) => {
  useDeepCompareEffect(() => {
    if (navigator && preferences) {
      const mappedPreferences = mapPreferencesToNavigator(
        preferences
      ) as unknown as EpubPreferences;
      navigator?.submitPreferences(mappedPreferences);
    }
  }, [preferences, !!navigator]);
};
