import type { CanvasModel } from "./canvasModel";
import { repairSections, type NodeGeometry } from "./edgeRepair";
import type { Layout } from "./layout";

/** 局部内容测量只更新所属组内部；未冲突的邻组及镜头保持原位。 */
export function preserveMeasuredLayout(model: CanvasModel, next: Layout, previous: Layout,
  current: NodeGeometry[], down: boolean): Layout {
  const byId = new Map(current.map(node => [node.id, node]));
  const resized = model.nodes.filter(node => node.kind !== "group" && previous.sizes[node.id]
    && (previous.sizes[node.id].width !== next.sizes[node.id]?.width
      || previous.sizes[node.id].height !== next.sizes[node.id]?.height));
  const changed = new Set(resized.map(node => node.parentId));
  if (!changed.size) return next;
  const positions = { ...next.positions }, sizes = { ...next.sizes };
  for (const node of model.nodes) {
    const old = byId.get(node.id)?.position ?? previous.positions[node.id];
    if (old) positions[node.id] = old;
    if (previous.sizes[node.id] && node.kind === "group" && !changed.has(node.id)) sizes[node.id] = previous.sizes[node.id];
  }

  // 保留组内排列，按实际尺寸差推动原本位于下方/右方的成员。
  // 整图 ELK 会受跨组边影响把纵排改成横排，不能把这种变宽当作真实内容增长。
  const before = { ...positions };
  const diagrams = new Map<string, { x: number; y: number; right: number; bottom: number }>();
  for (const node of model.nodes.filter(node => node.kind === "graph")) {
    const p = before[node.id], size = previous.sizes[node.id], old = diagrams.get(node.elementId);
    if (!p || !size) continue;
    diagrams.set(node.elementId, { x: Math.min(old?.x ?? p.x, p.x), y: Math.min(old?.y ?? p.y, p.y),
      right: Math.max(old?.right ?? p.x + size.width, p.x + size.width), bottom: Math.max(old?.bottom ?? p.y + size.height, p.y + size.height) });
  }
  for (const node of model.nodes.filter(node => node.kind !== "group" && changed.has(node.parentId))) {
    const point = before[node.id];
    let dx = 0, dy = 0;
    for (const resizedNode of resized.filter(other => other.parentId === node.parentId && other.id !== node.id)) {
      const p = before[resizedNode.id], old = previous.sizes[resizedNode.id], size = sizes[resizedNode.id];
      const own = previous.sizes[node.id];
      // 伴随内容推动 diagram 时整图一起移动，不拆散原本同排的流程节点。
      const box = node.kind === "graph" && resizedNode.kind === "companion" ? diagrams.get(node.elementId) : undefined;
      const bounds = box ?? { ...point, right: point.x + own.width, bottom: point.y + own.height };
      if (bounds.y >= p.y + old.height && bounds.x < p.x + old.width && bounds.right > p.x) dy += size.height - old.height;
      if (bounds.x >= p.x + old.width && bounds.y < p.y + old.height && bounds.bottom > p.y) dx += size.width - old.width;
    }
    positions[node.id] = { x: point.x + dx, y: point.y + dy };
  }
  for (const id of changed) {
    if (!id) continue;
    const members = model.nodes.filter(node => node.parentId === id);
    sizes[id] = {
      width: Math.max(0, ...members.map(node => positions[node.id].x + sizes[node.id].width)) + 20,
      height: Math.max(52, ...members.map(node => positions[node.id].y + sizes[node.id].height)) + 20,
    };
  }

  // 增长确实占用邻组空间时才避让；收起不会把已避让的组拉回，也不重排无关组。
  const roots = model.groups.map(group => group.nodeId);
  const ordered = [...roots.filter(id => changed.has(id)), ...roots.filter(id => !changed.has(id))];
  const placed: string[] = [];
  const overlapArea = (id: string, other: string, old: boolean, at?: { x: number; y: number }) => {
    const geometry = old ? previous.sizes : sizes, points = old ? before : positions;
    const a = geometry[id], b = geometry[other], p = at ?? points[id], q = points[other];
    if (!a || !b || !p || !q) return 0;
    return Math.max(0, Math.min(p.x + a.width + 24, q.x + b.width + 24) - Math.max(p.x, q.x))
      * Math.max(0, Math.min(p.y + a.height + 24, q.y + b.height + 24) - Math.max(p.y, q.y));
  };
  for (const id of ordered) {
    const point = positions[id], size = sizes[id];
    if (!point || !size) continue;
    if (placed.some(other => overlapArea(id, other, false) > overlapArea(id, other, true) + .5)) {
      const xs = [point.x], ys = [point.y];
      for (const other of placed) {
        const q = positions[other], s = sizes[other];
        const allowed = overlapArea(id, other, true);
        const overlapX = Math.max(0, Math.min(point.x + size.width + 24, q.x + s.width + 24) - Math.max(point.x, q.x));
        const overlapY = Math.max(0, Math.min(point.y + size.height + 24, q.y + s.height + 24) - Math.max(point.y, q.y));
        const keepX = overlapY ? allowed / overlapY : 0, keepY = overlapX ? allowed / overlapX : 0;
        xs.push(q.x - size.width - 24 + keepX, q.x + s.width + 24 - keepX,
          q.x - size.width - 24, q.x + s.width + 24);
        ys.push(q.y - size.height - 24 + keepY, q.y + s.height + 24 - keepY,
          q.y - size.height - 24, q.y + s.height + 24);
      }
      const candidates = [...xs.map(x => ({ x, y: point.y })), ...ys.map(y => ({ x: point.x, y }))];
      const distance = (p: { x: number; y: number }) => Math.abs(p.x - point.x) + Math.abs(p.y - point.y);
      positions[id] = candidates.filter(p => placed.every(other => overlapArea(id, other, false, p) <= overlapArea(id, other, true) + .5))
        .sort((a, b) => distance(a) - distance(b))[0] ?? point;
    }
    placed.push(id);
  }
  const nodes = model.nodes.map(node => ({ id: node.id, parentId: node.parentId,
    position: positions[node.id], ...sizes[node.id] }));
  // ELK 路径属于 next 的旧根坐标；按最终端点修线，不能只平移整张图。
  return { positions, sizes, sections: repairSections(model, next, nodes, down) };
}
