import type { Preferences } from '../interfaces';

type ThemeName = NonNullable<Preferences['theme']>;

const THEME_COLORS: Record<
  ThemeName,
  { backgroundColor: string; textColor: string }
> = {
  light: {
    backgroundColor: '#ffffff',
    textColor: '#000000',
  },
  dark: {
    backgroundColor: '#000000',
    textColor: '#ffffff',
  },
  sepia: {
    backgroundColor: '#f4ecd8',
    textColor: '#5f4b32',
  },
};

export type NavigatorPreferences = Omit<Preferences, 'pageMargins'> & {
  pageGutter?: number;
};

export function mapPreferencesToNavigator(
  preferences?: Preferences | null
): NavigatorPreferences | undefined {
  if (!preferences) {
    return undefined;
  }

  const mapped: NavigatorPreferences & { pageMargins?: number } = {
    ...preferences,
  };

  if (preferences.pageMargins !== undefined) {
    mapped.pageGutter = preferences.pageMargins;
    delete mapped.pageMargins;
  }

  if (
    preferences.theme &&
    !preferences.backgroundColor &&
    !preferences.textColor
  ) {
    const themeColors = THEME_COLORS[preferences.theme];
    mapped.backgroundColor = themeColors.backgroundColor;
    mapped.textColor = themeColors.textColor;
  }

  return mapped;
}