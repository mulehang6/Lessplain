export type PopupAnchor = { left: number; top: number; right: number; bottom: number };

/** DOM 坐标包含画布缩放，弹层固定在屏幕中，不再乘 viewport.zoom。 */
export function elementAnchor(element: Element): PopupAnchor {
  const { left, top, right, bottom } = element.getBoundingClientRect();
  return { left, top, right, bottom };
}

export function popupPosition(anchor: PopupAnchor | undefined, size: { width: number; height: number }, viewport: { width: number; height: number }) {
  const margin = 8, gap = 6;
  const clamp = (value: number, extent: number, total: number) => Math.max(margin, Math.min(value, total - extent - margin));
  if (!anchor) return { left: clamp((viewport.width - size.width) / 2, size.width, viewport.width), top: clamp((viewport.height - size.height) / 2, size.height, viewport.height) };
  const below = anchor.bottom + gap, above = anchor.top - gap - size.height;
  const top = below + size.height <= viewport.height - margin ? below : above >= margin ? above : clamp(below, size.height, viewport.height);
  return { left: clamp(anchor.left, size.width, viewport.width), top };
}
