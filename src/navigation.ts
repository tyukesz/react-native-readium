import { NativeModules, Platform } from 'react-native';
import type { RefObject } from 'react';
import type { Link, Locator } from './interfaces';
import { requireReactTag } from './utils/requireReactTag';

type NativeNavigationModule = {
  navigateTo: (reactTag: number, location: Locator | Link) => Promise<boolean>;
};

const NativeNavigation: NativeNavigationModule | undefined = (
  NativeModules as any
)?.NavigationModule;

export async function navigateTo(
  viewRef: RefObject<any> | any,
  location: Locator | Link
): Promise<boolean> {
  if (Platform.OS === 'web') {
    throw new Error('Navigation is not implemented on web yet');
  }

  if (!NativeNavigation?.navigateTo) {
    throw new Error('Native NavigationModule is not available');
  }

  return NativeNavigation.navigateTo(requireReactTag(viewRef), location);
}
