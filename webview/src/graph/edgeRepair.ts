import type { ElkEdgeSection } from "elkjs/lib/elk-api";
import type { CanvasModel } from "./canvasModel";
import type { Layout } from "./layout";

type Point = { x: number; y: number };
export type NodeGeometry = { id: string; parentId?: string; position: Point; width?: number; height?: number; measured?: { width?: number; height?: number } };
export type Box = { x: number; y: number; width: number; height: number };
const same = (a: Point, b: Point) => Math.abs(a.x - b.x) < .01 && Math.abs(a.y - b.y) < .01;
export const sectionPoints = (sections: ElkEdgeSection[]) => sections.flatMap(s => [s.startPoint, ...(s.bendPoints ?? []), s.endPoint]);

/** 将 RF 相对位置转为统一画布坐标；边不使用保存位置以外的旧镜头坐标。 */
export function nodeBoxes(nodes: NodeGeometry[]): Map<string, Box> {
  const byId = new Map(nodes.map(node => [node.id, node]));
  const absolute = (node: NodeGeometry): Point => {
    const parent = node.parentId ? byId.get(node.parentId) : undefined;
    const base = parent ? absolute(parent) : { x: 0, y: 0 };
    return { x: base.x + node.position.x, y: base.y + node.position.y };
  };
  return new Map(nodes.map(node => [node.id, { ...absolute(node), width: node.measured?.width ?? node.width ?? 220, height: node.measured?.height ?? node.height ?? 80 }]));
}

/** 只判定正交线段穿入框内部；边界接触和孤立交叉不算遮挡。 */
export function segmentHitsBox(a: Point, b: Point, box: Box): boolean {
  const left = box.x + .5, right = box.x + box.width - .5, top = box.y + .5, bottom = box.y + box.height - .5;
  if (a.x === b.x) return a.x > left && a.x < right && Math.max(a.y, b.y) > top && Math.min(a.y, b.y) < bottom;
  if (a.y === b.y) return a.y > top && a.y < bottom && Math.max(a.x, b.x) > left && Math.min(a.x, b.x) < right;
  return true;
}
const blocked = (points: Point[], boxes: Box[]) => points.slice(1).reduce((sum, p, i) => sum + boxes.filter(box => segmentHitsBox(points[i], p, box)).length, 0);
function simplify(points: Point[]): Point[] {
  const result: Point[] = [];
  for (const point of points) {
    if (result.length && same(result.at(-1)!, point)) continue;
    result.push(point);
    while (result.length >= 3) {
      const [a, b, c] = result.slice(-3);
      if ((a.x === b.x && b.x === c.x && (b.y - a.y) * (c.y - b.y) >= 0)
        || (a.y === b.y && b.y === c.y && (b.x - a.x) * (c.x - b.x) >= 0)) result.splice(result.length - 2, 1);
      else break;
    }
  }
  return result;
}
function sharedRuns(points: Point[], routes: Point[][]): number {
  let conflicts = 0;
  for (let i = 1; i < points.length; i++) for (const route of routes) for (let j = 1; j < route.length; j++) {
    const a = points[i - 1], b = points[i], c = route[j - 1], d = route[j];
    if (a.x === b.x && c.x === d.x && Math.abs(a.x - c.x) < 8
      && Math.min(Math.max(a.y, b.y), Math.max(c.y, d.y)) - Math.max(Math.min(a.y, b.y), Math.min(c.y, d.y)) > 12) conflicts++;
    if (a.y === b.y && c.y === d.y && Math.abs(a.y - c.y) < 8
      && Math.min(Math.max(a.x, b.x), Math.max(c.x, d.x)) - Math.max(Math.min(a.x, b.x), Math.min(c.x, d.x)) > 12) conflicts++;
  }
  return conflicts;
}

/** 沿用旧版的短 stub、障碍外通道与候选评分；只修线，不移动节点或运行 ELK。 */
function route(start: Point, end: Point, boxes: Box[], occupied: Point[][], down: boolean): Point[] {
  let best = [start, end], bestCost = Infinity;
  const channels = (axis: "x" | "y", extent: "width" | "height", mid: number) => [...new Set([
    mid, ...boxes.flatMap(b => [b[axis] - 24, b[axis] + b[extent] + 24]), start[axis] - 24, end[axis] + 24,
  ])].sort((a, b) => Math.abs(a - mid) - Math.abs(b - mid)).slice(0, 24);
  const xs = channels("x", "width", (start.x + end.x) / 2), ys = channels("y", "height", (start.y + end.y) / 2);
  for (const variant of [0, 12, -12, 24, -24]) {
    const stub = 18 + Math.abs(variant);
    const a = down ? { x: start.x, y: start.y + stub } : { x: start.x + stub, y: start.y };
    const b = down ? { x: end.x, y: end.y - stub } : { x: end.x - stub, y: end.y };
    const paths = [[a, { x: b.x, y: a.y }, b], [a, { x: a.x, y: b.y }, b],
      ...xs.map(x => [a, { x: x + variant, y: a.y }, { x: x + variant, y: b.y }, b]),
      ...ys.map(y => [a, { x: a.x, y: y + variant }, { x: b.x, y: y + variant }, b]),
      // 绕过起点/终点侧的阻塞，也覆盖回边与自环。
      ...xs.flatMap(x => ys.map(y => [a, { x, y: a.y }, { x, y }, { x: b.x, y }, b])),
    ];
    for (const inner of paths) {
      const points = simplify([start, ...inner, end]);
      const length = points.slice(1).reduce((sum, p, i) => sum + Math.abs(p.x - points[i].x) + Math.abs(p.y - points[i].y), 0);
      const cost = blocked(points, boxes) * 1e9 + sharedRuns(points, occupied) * 1e5 + length + points.length * 8;
      if (cost < bestCost) { best = points; bestCost = cost; }
      if (cost < 1e5) return points;
    }
    if (bestCost < 1e5) break;
  }
  return best;
}

/** 无变化的边保留对象；分组内整体移动的路径同步平移，受影响边才局部重建。 */
export function repairSections(model: CanvasModel, layout: Layout, nodes: NodeGeometry[], down: boolean): Record<string, ElkEdgeSection[]> {
  const current = nodeBoxes(nodes);
  const baseline = nodeBoxes(model.nodes.map(n => ({ id: n.id, parentId: n.parentId, position: layout.positions[n.id] ?? { x: 0, y: 0 }, ...layout.sizes[n.id] })));
  const obstacles = model.nodes.filter(n => n.kind !== "group").flatMap(n => current.get(n.id) ? [current.get(n.id)!] : []);
  const result: Record<string, ElkEdgeSection[]> = {};
  const occupied: Point[][] = [];
  const affected: { id: string; points: Point[]; start: Point; end: Point; source: string; target: string }[] = [];
  for (const edge of model.edges) {
    const original = layout.sections[edge.id];
    if (!original?.length) continue;
    const s = current.get(edge.source), t = current.get(edge.target), bs = baseline.get(edge.source), bt = baseline.get(edge.target);
    if (!s || !t || !bs || !bt) continue;
    const ds = { x: s.x - bs.x, y: s.y - bs.y }, dt = { x: t.x - bt.x, y: t.y - bt.y };
    const points = sectionPoints(original).map(p => ({ x: p.x + ds.x, y: p.y + ds.y }));
    const start = { x: original[0].startPoint.x + ds.x, y: original[0].startPoint.y + ds.y };
    const end = { x: original.at(-1)!.endPoint.x + dt.x, y: original.at(-1)!.endPoint.y + dt.y };
    const hasMoved = !same(ds, dt);
    if (!hasMoved && !blocked(points, obstacles)) {
      result[edge.id] = same(ds, { x: 0, y: 0 }) ? original : original.map(section => ({ ...section,
        startPoint: { x: section.startPoint.x + ds.x, y: section.startPoint.y + ds.y },
        endPoint: { x: section.endPoint.x + ds.x, y: section.endPoint.y + ds.y },
        bendPoints: section.bendPoints?.map(p => ({ x: p.x + ds.x, y: p.y + ds.y })),
      }));
      occupied.push(points);
    } else affected.push({ id: edge.id, points, start, end, source: edge.source, target: edge.target });
  }
  for (const edge of affected) {
    const points = route(edge.start, edge.end, obstacles, occupied, down);
    result[edge.id] = [{ id: edge.id + ":repair", startPoint: points[0], endPoint: points.at(-1)!, bendPoints: points.slice(1, -1) }];
    occupied.push(points);
  }
  return result;
}

/** 标签跟随当前路径的最长线段，避开路径端点及箭头。 */
export function edgeLabelPoint(sections: ElkEdgeSection[]): Point {
  const points = sectionPoints(sections);
  let segment = [points[0] ?? { x: 0, y: 0 }, points.at(-1) ?? { x: 0, y: 0 }], length = -1;
  for (let i = 1; i < points.length; i++) {
    const size = Math.abs(points[i].x - points[i - 1].x) + Math.abs(points[i].y - points[i - 1].y);
    if (size > length) { length = size; segment = [points[i - 1], points[i]]; }
  }
  return { x: (segment[0].x + segment[1].x) / 2, y: (segment[0].y + segment[1].y) / 2 };
}
