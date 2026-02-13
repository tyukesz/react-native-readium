import { NativeModules, Platform } from 'react-native';
import type { OpenPublicationHeadlessInput, PublicationIndex } from './interfaces/Headless';

type NativeHeadlessModule = {
  openPublicationHeadless: (input: OpenPublicationHeadlessInput) => Promise<PublicationIndex>;
  cancelHeadless: (id: string) => void;
};

const NativeHeadless: NativeHeadlessModule | undefined = (NativeModules as any)
  ?.HeadlessModule;

export async function openPublicationHeadless(
  input: OpenPublicationHeadlessInput
): Promise<PublicationIndex> {
  if (Platform.OS === 'web') {
    throw new Error('Headless publication indexing is not implemented on web');
  }
  if (!NativeHeadless?.openPublicationHeadless) {
    throw new Error('Native HeadlessModule is not available');
  }
  if (!input?.url) {
    throw new Error('openPublicationHeadless: `url` is required');
  }
  return NativeHeadless.openPublicationHeadless(input);
}

/** Cancels a pending headless indexing job started with the same `id`. */
export function cancelHeadless(id: string) {
  if (Platform.OS === 'web') return;
  if (!NativeHeadless?.cancelHeadless) {
    throw new Error('Native HeadlessModule is not available');
  }
  if (!id) return;
  NativeHeadless.cancelHeadless(id);
}
