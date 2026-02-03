import type { Locator } from './interfaces';

const positionsByReactTag = new Map<number, Locator[]>();

export function setPositionsForReactTag(
  reactTag: number,
  positions: Locator[] | null | undefined
): void {
  if (!Number.isFinite(reactTag)) return;
  positionsByReactTag.set(reactTag, Array.isArray(positions) ? positions : []);
}

export function getPositionsForReactTag(
  reactTag: number
): Locator[] | undefined {
  if (!Number.isFinite(reactTag)) return undefined;
  return positionsByReactTag.get(reactTag);
}

export function clearPositionsForReactTag(reactTag: number): void {
  positionsByReactTag.delete(reactTag);
}
