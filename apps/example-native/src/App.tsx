import React, { useState } from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { Reader, RNFS } from 'common-app';
import type { Link, Locator } from '@tyukesz/react-native-readium';

import { TableOfContentsScreen } from './TableOfContentsScreen';

const Stack = createNativeStackNavigator();

export default function App() {
  const [toc, setToc] = useState<Link[]>([]);
  const [externalLocation, setExternalLocation] = useState<Locator | Link>();

  return (
    <NavigationContainer>
      <Stack.Navigator initialRouteName="Reader">
        <Stack.Screen name="Reader">
          {({ navigation }) => (
            <Reader
              epubUrl="https://test.opds.io/assets/moby/file.epub"
              epubPath={`${RNFS.DocumentDirectoryPath}/moby-dick.epub`}
              initialLocation={{
                href: '/OPS/main3.xml',
                title: 'Chapter 2 - The Carpet-Bag',
                type: 'application/xhtml+xml',
                target: 27,
                locations: {
                  position: 24,
                  progression: 0,
                  totalProgression: 0.03392330383480826,
                },
              }}
              externalLocation={externalLocation}
              onTocChange={setToc}
              onOpenToc={() => navigation.navigate('TableOfContents')}
            />
          )}
        </Stack.Screen>
        <Stack.Screen name="TableOfContents" options={{ title: 'Table of Contents' }}>
          {({ navigation }) => (
            <TableOfContentsScreen
              items={toc}
              onSelect={(loc) => {
                setExternalLocation(loc);
                navigation.goBack();
              }}
            />
          )}
        </Stack.Screen>
      </Stack.Navigator>
    </NavigationContainer>
  );
}
