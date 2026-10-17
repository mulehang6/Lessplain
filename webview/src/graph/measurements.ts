import type { Measure } from "./layout";

type NodeChangeLike = {
  id?: string;
  type: string;
  dimensions?: { width: number; height: number };
};

/** Apply only measured dimensions; position/selection changes preserve the same array. */
export function mergeMeasuredSizes(current: Measure[], changes: readonly NodeChangeLike[]): Measure[] {
  let next: Map<string, Measure> | undefined;
  let changed = false;
  for (const change of changes) {
    const dimensions = change.dimensions;
    if (change.type !== "dimensions" || typeof change.id !== "string" || !dimensions
      || !Number.isFinite(dimensions.width) || !Number.isFinite(dimensions.height)
      || dimensions.width <= 0 || dimensions.height <= 0) continue;
    next ??= new Map(current.map(measure => [measure.id, measure]));
    const previous = next.get(change.id);
    if (previous?.width === dimensions.width && previous.height === dimensions.height) continue;
    next.set(change.id, { id: change.id, width: dimensions.width, height: dimensions.height });
    changed = true;
  }
  return changed ? [...next!.values()] : current;
}

/** Drop dimensions for nodes removed by a structural spec change. */
export function retainMeasuredSizes(current: Measure[], activeIds: ReadonlySet<string>): Measure[] {
  if (current.every(measure => activeIds.has(measure.id))) return current;
  return current.filter(measure => activeIds.has(measure.id));
}
