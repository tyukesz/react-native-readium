import React, { useCallback } from 'react';
import { View, StyleSheet } from 'react-native';
import type { Link, Locator } from '@tyukesz/react-native-readium';

import { TableOfContents } from 'common-app';

export interface TableOfContentsScreenProps {
  items: Link[];
  onSelect: (locator: Locator | Link) => void;
}

const DEFAULT_CONTENT_TYPE = 'application/xhtml+xml';

const toLocator = (link: Link): Locator => ({
  href: link.href,
  type: link.type || DEFAULT_CONTENT_TYPE,
  title: link.title || '',
  locations: {
    progression: 0,
  },
});

export const TableOfContentsScreen: React.FC<TableOfContentsScreenProps> = ({
  items,
  onSelect,
}) => {
  const handlePress = useCallback(
    (loc: Link) => {
      onSelect(toLocator(loc));
    },
    [onSelect]
  );

  return (
    <View style={styles.container}>
      <TableOfContents items={items} onPress={handlePress} />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
  },
});
