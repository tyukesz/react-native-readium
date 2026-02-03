import React, { useMemo } from 'react';
import { Text, ScrollView, View, StyleSheet } from 'react-native';
import { ListItem } from '@rneui/themed';
import type { Link } from '@tyukesz/react-native-readium';

export interface TableOfContentsProps {
  items?: Link[] | null;
  onPress?: (locator: Link) => void;
  title?: string;
}

export const TableOfContents: React.FC<TableOfContentsProps> = ({
  items: externalItems,
  onPress,
  title = 'Table of Contents',
}) => {
  const items = useMemo(() => externalItems || [], [externalItems]);

  return (
    <View style={styles.container}>
      <Text style={styles.title}>{title}</Text>
      <ScrollView style={styles.list}>
        {items.map((item, idx) => {
          return (
            <ListItem
              key={idx}
              onPress={() => {
                if (onPress) onPress(item);
              }}
              bottomDivider={items.length - 1 !== idx}
            >
              <ListItem.Content>
                <ListItem.Title>
                  {item.title ? item.title : `Chapter ${idx + 1}`}
                </ListItem.Title>
              </ListItem.Content>
              <ListItem.Chevron />
            </ListItem>
          );
        })}
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 12,
  },
  list: {
    maxHeight: '100%',
    width: '100%',
  },
});
