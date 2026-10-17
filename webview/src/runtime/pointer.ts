/** Decode an RFC 6901 JSON Pointer into its path segments. */
export function pointerSegments(pointer: string): string[] | undefined {
  if (pointer === "") return [];
  if (!pointer.startsWith("/")) return undefined;
  const result: string[] = [];
  for (const raw of pointer.slice(1).split("/")) {
    if (/~(?![01])/.test(raw)) return undefined;
    result.push(raw.replace(/~1/g, "/").replace(/~0/g, "~"));
  }
  return result;
}

/** Read a value addressed by an RFC 6901 JSON Pointer. */
export function readPointer(value: unknown, pointer: string): unknown {
  const segments = pointerSegments(pointer);
  if (!segments) return undefined;
  let current = value;
  for (const segment of segments) {
    if (Array.isArray(current)) {
      const index = arrayIndex(segment);
      if (index === undefined || index >= current.length) return undefined;
      current = current[index];
    } else if (isRecord(current) && Object.hasOwn(current, segment)) {
      current = current[segment];
    } else {
      return undefined;
    }
  }
  return current;
}

/** Return an immutable value update, or undefined when the pointer cannot be written. */
export function updatePointer(value: unknown, pointer: string, nextValue: unknown): { value: unknown; changed: boolean } | undefined {
  const segments = pointerSegments(pointer);
  if (!segments?.length) return undefined;
  const updated = updateAt(value, segments, 0, nextValue);
  return updated;
}

function updateAt(current: unknown, segments: string[], offset: number, nextValue: unknown): { value: unknown; changed: boolean } | undefined {
  const segment = segments[offset];
  const last = offset === segments.length - 1;
  if (Array.isArray(current)) {
    const index = arrayIndex(segment);
    if (index === undefined || index >= current.length) return undefined;
    if (last) {
      if (Object.is(current[index], nextValue)) return { value: current, changed: false };
      const copy = current.slice();
      copy[index] = nextValue;
      return { value: copy, changed: true };
    }
    const nested = updateAt(current[index], segments, offset + 1, nextValue);
    if (!nested) return undefined;
    if (!nested.changed) return { value: current, changed: false };
    const copy = current.slice();
    copy[index] = nested.value;
    return { value: copy, changed: true };
  }

  const record = current === undefined ? {} : isRecord(current) ? current : undefined;
  if (!record) return undefined;
  if (last) {
    if (Object.hasOwn(record, segment) && Object.is(record[segment], nextValue)) return { value: record, changed: false };
    const copy = { ...record };
    Object.defineProperty(copy, segment, { value: nextValue, enumerable: true, configurable: true, writable: true });
    return { value: copy, changed: true };
  }
  const nested = updateAt(record[segment], segments, offset + 1, nextValue);
  if (!nested) return undefined;
  if (!nested.changed) return { value: current, changed: false };
  const copy = { ...record };
  Object.defineProperty(copy, segment, { value: nested.value, enumerable: true, configurable: true, writable: true });
  return { value: copy, changed: true };
}

function arrayIndex(segment: string): number | undefined {
  if (!/^(0|[1-9]\d*)$/.test(segment)) return undefined;
  const index = Number(segment);
  return Number.isSafeInteger(index) ? index : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
