import React, {
  useCallback,
  useEffect,
  forwardRef,
  useRef,
  useMemo,
  useState,
} from 'react';
import { Platform, StyleSheet, View } from 'react-native';

import type {
  BaseReadiumViewProps,
  Dimensions,
  Preferences,
} from '../interfaces';
import {
  getWidthOrHeightValue as dimension,
  mapPreferencesToNavigator,
} from '../utils';
import { BaseReadiumView } from './BaseReadiumView';
import { Commands } from '../specs/ReadiumViewNativeComponent';

export type ReadiumProps = Omit<BaseReadiumViewProps, 'preferences'> & {
  preferences: Preferences;
};

const normalizeHref = (href: string): string => {
  const trimmed = href.trim().replace(/^\/+/, '');
  const noFragment = trimmed.split('#')[0] ?? trimmed;
  const noQuery = noFragment.split('?')[0] ?? noFragment;

  try {
    return decodeURIComponent(noQuery);
  } catch {
    return noQuery;
  }
};

export const ReadiumView: React.FC<ReadiumProps> = forwardRef(
  (
    {
      onLocationChange: wrappedOnLocationChange,
      onPublicationReady: wrappedOnPublicationReady,
      onRestrictedNavigation: wrappedOnRestrictedNavigation,
      onTap: wrappedOnTap,
      allowedHrefs,
      paywallHTML,
      preferences,
      ...props
    },
    forwardedRef
  ) => {
    const defaultRef = useRef<any>(null);
    const [{ height, width }, setDimensions] = useState<Dimensions>({
      width: 0,
      height: 0,
    });

    // set the view dimensions on layout
    const onLayout = useCallback(
      ({
        nativeEvent: {
          layout: { width: layoutWidth, height: layoutHeight },
        },
      }: any) => {
        setDimensions({
          width: dimension(layoutWidth),
          height: dimension(layoutHeight),
        });
      },
      []
    );

    // wrap the native onLocationChange and extract the raw event value
    const onLocationChange = useCallback(
      (event: any) => {
        if (wrappedOnLocationChange) {
          wrappedOnLocationChange(event.nativeEvent);
        }
      },
      [wrappedOnLocationChange]
    );

    const onPublicationReady = useCallback(
      (event: any) => {
        if (wrappedOnPublicationReady) {
          wrappedOnPublicationReady(event.nativeEvent);
        }
      },
      [wrappedOnPublicationReady]
    );

    const onRestrictedNavigation = useCallback(
      (event: any) => {
        const href = event?.nativeEvent?.href;
        if (typeof href === 'string') {
          if (wrappedOnRestrictedNavigation) {
            wrappedOnRestrictedNavigation(href.length === 0 ? '' : normalizeHref(href));
          }
        }
      },
      [wrappedOnRestrictedNavigation]
    );

    const onTap = useCallback(
      (event: any) => {
        if (wrappedOnTap) {
          wrappedOnTap(event.nativeEvent);
        }
      },
      [wrappedOnTap]
    );

    // create the view fragment on android
    useEffect(() => {
      if (Platform.OS === 'android' && defaultRef.current) {
        Commands.create(defaultRef.current);
      }
    }, []);

    // assign the forwarded ref
    const hasDefaultRef = defaultRef.current !== null;
    useEffect(() => {
      if (forwardedRef && 'current' in forwardedRef) {
        forwardedRef.current = defaultRef.current;
      } else if (forwardedRef) {
        forwardedRef(defaultRef);
      }
    }, [forwardedRef, hasDefaultRef, defaultRef]);

    const stringifiedPreferences = useMemo(() => {
      return JSON.stringify(mapPreferencesToNavigator(preferences));
    }, [preferences]);

    const stringifiedAllowedHrefs = useMemo(() => {
      if (allowedHrefs === undefined) {
        return undefined;
      }

      return JSON.stringify(allowedHrefs.map(normalizeHref));
    }, [allowedHrefs]);

    return (
      <View style={styles.container} onLayout={onLayout}>
        <BaseReadiumView
          height={height}
          width={width}
          {...(props as any)}
          preferences={stringifiedPreferences}
          allowedHrefs={stringifiedAllowedHrefs}
          paywallHTML={paywallHTML}
          onLocationChange={onLocationChange}
          onPublicationReady={onPublicationReady}
          onRestrictedNavigation={onRestrictedNavigation}
          onTap={onTap}
          ref={defaultRef}
        />
      </View>
    );
  }
);

const styles = StyleSheet.create({
  container: { width: '100%', height: '100%' },
});
