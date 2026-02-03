import { useEffect, useRef, useState } from 'react';
import type { Link, Locator } from '@tyukesz/react-native-readium';

export const useExternalLocation = (externalLocation?: Locator | Link) => {
  const [location, setLocation] = useState<Locator | Link>();
  const lastExternalLocation = useRef<Locator | Link | undefined>(undefined);

  useEffect(() => {
    if (!externalLocation) return;
    if (lastExternalLocation.current === externalLocation) return;
    lastExternalLocation.current = externalLocation;
    setLocation(externalLocation);
  }, [externalLocation]);

  return { location, setLocation };
};
