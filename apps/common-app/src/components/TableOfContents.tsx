import React from 'react';
import { Text, ScrollView, View } from 'react-native';
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
  const items = externalItems || [];

  return (
    <View style={{ flex: 1 }}>
      <Text style={{ fontSize: 18, fontWeight: '600', marginBottom: 12 }}>
        {title}
      </Text>
      <ScrollView style={{ maxHeight: '100%', width: '100%' }}>
        {items.map((item, idx) => (
          <ListItem
            key={idx}
            onPress={() => {
              if (onPress) {
                onPress(item);
              }
            }}
            bottomDivider={items.length - 1 != idx}
          >
            <ListItem.Content>
              <ListItem.Title>
                {item.title ? item.title : `Chapter ${idx + 1}`}
              </ListItem.Title>
            </ListItem.Content>
            <ListItem.Chevron />
          </ListItem>
        ))}
      </ScrollView>
    </View>
  );
};
