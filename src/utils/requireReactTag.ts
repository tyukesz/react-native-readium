import { findNodeHandle } from 'react-native';
import type { RefObject } from 'react';

export function requireReactTag(viewRef: RefObject<any> | any): number {
  const node = viewRef && 'current' in viewRef ? viewRef.current : viewRef;
  const reactTag = findNodeHandle(node);
  if (!reactTag) {
    throw new Error('Could not resolve reactTag for ReadiumView');
  }
  return reactTag;
}
