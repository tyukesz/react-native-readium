import type { ViewStyle } from 'react-native';
import type { Link } from './Link';
import type { Locator } from './Locator';
import type { File } from './File';

export type TocItem = Link & {
  children?: TocItem[];
  startPosition: number | null;
  endPosition: number | null;
};

export type TableOfContentsPayload = {
  toc: TocItem[] | null;
  totalPositions: number | null;
};

export type BaseReadiumViewProps = {
  file: File;
  location?: Locator | Link;
  preferences?: string; // JSON between native and JS, which we deserialise later
  hidePageNumbers?: boolean; // Show or hide native position label (iOS only for now)
  style?: ViewStyle;
  onLocationChange?: (locator: Locator) => void;
  onTableOfContents?: (payload: TableOfContentsPayload) => void;
  ref?: any;
  height?: number;
  width?: number;
};
