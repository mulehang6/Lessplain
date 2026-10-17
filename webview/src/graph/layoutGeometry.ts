import type { Layout } from "./layout";

/** 内容增长后保留触发分组的画布位置；只平移顶层坐标和全局边，子节点仍用相对坐标。 */
export function anchorLayout(layout: Layout, roots: string[], anchorId: string, target: { x: number; y: number }): Layout {
  const anchor = layout.positions[anchorId];
  if (!anchor) return layout;
  const dx = target.x - anchor.x, dy = target.y - anchor.y;
  const translate = (point: { x: number; y: number }) => ({ x: point.x + dx, y: point.y + dy });
  const rootIds = new Set(roots);
  return { ...layout,
    positions: Object.fromEntries(Object.entries(layout.positions).map(([id, point]) => [id, rootIds.has(id) ? translate(point) : point])),
    sections: Object.fromEntries(Object.entries(layout.sections).map(([id, sections]) => [id, sections.map(section => ({ ...section,
      startPoint: translate(section.startPoint), endPoint: translate(section.endPoint),
      ...(section.bendPoints ? { bendPoints: section.bendPoints.map(translate) } : {}),
    }))])),
  };
}
