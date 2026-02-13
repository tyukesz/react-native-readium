import type { Link } from './Link';
import type { PublicationReadyEvent } from './PublicationReady';

export type OpenPublicationHeadlessInput = {
  /** Local file URL (file://...) or absolute path ("/var/..."), matching `ReadiumView` input support. */
  url: string;
  /** Optional media type hint (currently best-effort; native may ignore). */
  mediaType?: string;
  /** Optional correlation id used for cancellation. */
  id?: string;
};

/**
 * Headless publication index result.
 *
 * Shapes are intentionally aligned with `PublicationReadyEvent` so app code can reuse the same
 * mapping logic used by `ReadiumView` `onPublicationReady`.
 */
export type PublicationIndex = PublicationReadyEvent & {
  /** Nice-to-have: the publication reading order (spine). */
  readingOrder?: Link[];
};

export type HeadlessErrorCode =
  | 'E_PUBLICATION_OPEN_FAILED'
  | 'E_DRM_NEEDS_USER_INTERACTION'
  | 'E_UNSUPPORTED_FORMAT'
  | 'E_CANCELLED';
