import React, { useCallback, useMemo, useState } from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { Reader } from 'common-app';
import type { Link, Locator } from '@tyukesz/react-native-readium';

import { TableOfContentsScreen } from './TableOfContentsScreen';
import { EPUB_URL, getEpubPath, INITIAL_LOCATION } from './readerConfig';

type RootStackParamList = {
  Reader: undefined;
  TableOfContents: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export default function App() {
  const [toc, setToc] = useState<Link[]>([]);
  const [externalLocation, setExternalLocation] = useState<Locator | Link>();

  const epubPath = useMemo(() => getEpubPath(), []);
  const handleOpenToc = useCallback(
    (navigation: { navigate: (screen: keyof RootStackParamList) => void }) =>
      navigation.navigate('TableOfContents'),
    []
  );
  const handleSelectToc = useCallback(
    (
      navigation: { goBack: () => void },
      location: Locator | Link
    ) => {
      setExternalLocation(location);
      navigation.goBack();
    },
    []
  );

  return (
    <NavigationContainer>
      <Stack.Navigator initialRouteName="Reader">
        <Stack.Screen name="Reader">
          {({ navigation }) => (
            <Reader
              epubUrl={EPUB_URL}
              epubPath={epubPath}
              initialLocation={INITIAL_LOCATION}
              externalLocation={externalLocation}
              limitToFirstTwoChapters={true}
              onTocChange={setToc}
              onOpenToc={() => handleOpenToc(navigation)}
            />
          )}
        </Stack.Screen>
        <Stack.Screen name="TableOfContents" options={{ title: 'Table of Contents' }}>
          {({ navigation }) => (
            <TableOfContentsScreen
              items={toc}
              onSelect={(loc) => handleSelectToc(navigation, loc)}
            />
          )}
        </Stack.Screen>
      </Stack.Navigator>
    </NavigationContainer>
  );
}
