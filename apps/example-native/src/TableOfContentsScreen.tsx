import React from 'react';
import { View, StyleSheet } from 'react-native';
import type { Link, Locator } from 'react-native-readium';

import { TableOfContents } from 'common-app';

export interface TableOfContentsScreenProps {
  items: Link[];
  onSelect: (locator: Locator | Link) => void;
}

export const TableOfContentsScreen: React.FC<TableOfContentsScreenProps> = ({
  items,
  onSelect,
}) => {
  return (
    <View style={styles.container}>
      <TableOfContents
        items={items}
        onPress={(loc) =>
          onSelect({
            href: loc.href,
            type: loc.type || 'application/xhtml+xml',
            title: loc.title || '',
            locations: {
              progression: 0,
            },
          })
        }
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 16,
  },
});
