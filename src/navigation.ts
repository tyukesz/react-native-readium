import { NativeModules, Platform } from 'react-native';
import type { RefObject } from 'react';
import type { Link, Locator } from './interfaces';
import { getPositionsForReactTag } from './navigationCache';
import { requireReactTag } from './utils/requireReactTag';

export type NavigateToProgressionParams = {
  href: string;
  progression: number;
  type?: string;
};

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

export async function navigateToProgression(
  viewRef: RefObject<any> | any,
  params: NavigateToProgressionParams
): Promise<boolean> {
  const href = params?.href?.trim();
  if (!href) {
    throw new Error('href is required');
  }

  const progression = Number(params.progression);
  if (!Number.isFinite(progression)) {
    throw new Error('Invalid progression');
  }

  // If we have publication positions (cached from onPublicationReady), navigate to the page
  // that *contains* the target progression (floor), rather than snapping to the nearest page.
  const reactTag = requireReactTag(viewRef);
  const positions = getPositionsForReactTag(reactTag) || [];
  if (positions.length) {
    const key = normalizeHref(href);
    const candidates = positions
      .filter((p) => normalizeHref(p?.href || '') === key)
      .filter((p) => Number.isFinite(p?.locations?.progression));

    if (candidates.length) {
      const sorted = candidates
        .slice()
        .sort(
          (a, b) =>
            Number(a.locations?.progression ?? 0) -
            Number(b.locations?.progression ?? 0)
        );

      // Floor to the containing page.
      const EPS = 1e-9;
      let chosen: Locator = sorted[0];
      for (const loc of sorted) {
        const p = Number(loc.locations?.progression);
        if (p <= progression + EPS) {
          chosen = loc;
        } else {
          break;
        }
      }

      return NativeNavigation!.navigateTo(reactTag, chosen);
    }
  }

  // Fallback: direct progression-based locator.
  return NativeNavigation!.navigateTo(reactTag, {
    href,
    type: params.type || 'application/xhtml+xml',
    locations: {
      progression,
    },
  });
}

function normalizeHref(href: string): string {
  // Normalize for matching publication positions.
  // Native locators may include a fragment (`#...`) or query (`?...`).
  return href
    .trim()
    .replace(/^\/+/, '')
    .split('#')[0]!
    .split('?')[0]!
    .replace(/\s+/g, '');
}
