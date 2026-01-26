/**
 * Event payload for onTap callback
 * Emitted when the reader view is tapped
 * Native only (not supported on web)
 */
export interface TapEvent {
  /** X coordinate of the tap in view space (React Native points) */
  x: number;

  /** Y coordinate of the tap in view space (React Native points) */
  y: number;
}
