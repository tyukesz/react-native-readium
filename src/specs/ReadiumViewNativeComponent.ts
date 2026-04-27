import type { HostComponent, ViewProps } from 'react-native';
import type {
  DirectEventHandler,
  Double,
} from 'react-native/Libraries/Types/CodegenTypes';
// eslint-disable-next-line @react-native/no-deep-imports
import codegenNativeComponent from 'react-native/Libraries/Utilities/codegenNativeComponent';
// eslint-disable-next-line @react-native/no-deep-imports
import codegenNativeCommands from 'react-native/Libraries/Utilities/codegenNativeCommands';

type OnLocationChangeEvent = Readonly<{ locatorJson: string }>;
type OnPublicationReadyEvent = Readonly<{ payloadJson: string }>;
type OnTapEvent = Readonly<{ x: Double; y: Double }>;
type OnRestrictedNavigationEvent = Readonly<{ href: string }>;

export interface NativeProps extends ViewProps {
  file: string;
  location?: string;
  preferences?: string;
  allowedHrefs?: string;
  paywallHTML?: string;
  hidePageNumbers?: boolean;
  enableTapNavigation?: boolean;
  disableTextSelection?: boolean;
  onLocationChange?: DirectEventHandler<OnLocationChangeEvent>;
  onPublicationReady?: DirectEventHandler<OnPublicationReadyEvent>;
  onRestrictedNavigation?: DirectEventHandler<OnRestrictedNavigationEvent>;
  onTap?: DirectEventHandler<OnTapEvent>;
}

export interface NativeCommands {
  create: (viewRef: React.ElementRef<HostComponent<NativeProps>>) => void;
}

export default codegenNativeComponent<NativeProps>(
  'ReadiumView'
) as HostComponent<NativeProps>;

export const Commands = codegenNativeCommands<NativeCommands>({
  supportedCommands: ['create'],
});
