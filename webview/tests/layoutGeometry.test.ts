import { expect, it } from "vitest";
import { preserveMeasuredLayout } from "../src/graph/layoutGeometry";
import type { CanvasModel } from "../src/graph/canvasModel";
import type { Layout } from "../src/graph/layout";

const model: CanvasModel = {
  boardId: "board", graphProps: {},
  groups: ["a", "b", "c"].map(id => ({ id, nodeId: id, title: id, companions: [id + "Child"] })),
  nodes: ["a", "b", "c"].flatMap(id => [
    { id, kind: "group" as const, elementId: id, nodeId: id, label: id },
    { id: id + "Child", kind: "companion" as const, parentId: id, elementId: id + "Child", nodeId: id + "Child", label: id },
  ]),
  edges: [{ id: "edge", source: "aChild", target: "bChild", kind: "control_flow",
    sourceElementId: "a", sourceNodeId: "aChild", targetElementId: "b", targetNodeId: "bChild" }],
};
function fixture(height = 100): Layout {
  return {
    positions: { a: { x: 0, y: 0 }, b: { x: 400, y: 0 }, c: { x: 800, y: 0 },
      aChild: { x: 20, y: 60 }, bChild: { x: 20, y: 60 }, cChild: { x: 20, y: 60 } },
    sizes: { a: { width: 240, height: height + 80 }, b: { width: 240, height: 180 }, c: { width: 240, height: 180 },
      aChild: { width: 200, height }, bChild: { width: 200, height: 100 }, cChild: { width: 200, height: 100 } },
    sections: { edge: [{ id: "s", startPoint: { x: 220, y: 110 }, endPoint: { x: 420, y: 110 } }] },
  };
}
const nodes = (layout: Layout) => model.nodes.map(n => ({ id: n.id, parentId: n.parentId,
  position: layout.positions[n.id], ...layout.sizes[n.id] }));

it("局部增长或收缩均保留所有未冲突分组及子节点，跨组边连接最终端点", () => {
  const previous = fixture(), next = fixture(300);
  // 模拟 ELK 因单个组件变化而把整张图重新居中、移动邻组和邻组内部节点。
  next.positions.a = { x: 100, y: 100 }; next.positions.b = { x: 600, y: 100 };
  next.positions.aChild = { x: 1000, y: 60 }; next.sizes.a.width = 9999;
  next.positions.bChild = { x: 60, y: 90 };
  next.sections.edge = [{ id: "s", startPoint: { x: 1300, y: 210 }, endPoint: { x: 660, y: 240 } }];
  const result = preserveMeasuredLayout(model, next, previous, nodes(previous), false);
  expect(result.positions).toEqual(previous.positions);
  expect(result.sizes.a.height).toBe(380);
  expect(result.sizes.a.width).toBe(240);
  expect(result.sections.edge[0].startPoint).toEqual({ x: 220, y: 110 });
  expect(result.sections.edge.at(-1)!.endPoint).toEqual({ x: 420, y: 110 });
  expect(preserveMeasuredLayout(model, previous, result, nodes(result), false).positions).toEqual(previous.positions);
  expect(next.positions.a).toEqual({ x: 100, y: 100 });
});

it("只有实际发生外框冲突的邻组才最小避让，收起后不拉回邻组", () => {
  const previous = fixture(), next = fixture();
  previous.positions.b = { x: 0, y: 250 };
  next.sizes.aChild.height = 300; next.sizes.a.height = 380;
  const result = preserveMeasuredLayout(model, next, previous, nodes(previous), true);
  expect(result.positions.a).toEqual(previous.positions.a);
  expect(result.positions.b).toEqual({ x: 0, y: 404 });
  expect(result.positions.c).toEqual(previous.positions.c);
  const closed = preserveMeasuredLayout(model, fixture(), result, nodes(result), true);
  expect(closed.positions.b).toEqual(result.positions.b);
});

it("没有成员尺寸变化时允许显式方向或结构布局生效", () => {
  const previous = fixture(), next = fixture(); next.positions.b = { x: 0, y: 500 };
  expect(preserveMeasuredLayout(model, next, previous, nodes(previous), true)).toBe(next);
});

it("其他组增长不整理用户原有的重叠，收缩也不触发新的避让", () => {
  const previous = fixture(), next = fixture(); previous.positions.b = { x: 0, y: 100 };
  next.sizes.cChild.height = 200;
  expect(preserveMeasuredLayout(model, next, previous, nodes(previous), false).positions.b).toEqual(previous.positions.b);
  const smaller = fixture(40);
  expect(preserveMeasuredLayout(model, smaller, previous, nodes(previous), false).positions.b).toEqual(previous.positions.b);
});

it("增长仅消除新增的重叠，不强行整理用户原有的重叠", () => {
  const previous = fixture(), next = fixture(300); previous.positions.b = { x: 0, y: 100 };
  const result = preserveMeasuredLayout(model, next, previous, nodes(previous), false);
  const p = result.positions.b, a = result.sizes.a, b = result.sizes.b;
  const area = Math.max(0, Math.min(a.width + 24, p.x + b.width + 24) - Math.max(0, p.x))
    * Math.max(0, Math.min(a.height + 24, p.y + b.height + 24) - Math.max(0, p.y));
  expect(area).toBeCloseTo(264 * 104);
  expect(Math.abs(p.x) + Math.abs(p.y - 100)).toBeLessThan(200);
});

it("成员保持原来的纵排，按真实高度差下推并在收起时收回组内空间", () => {
  const localModel: CanvasModel = { ...model, nodes: [...model.nodes,
    { id: "below", kind: "graph", parentId: "a", elementId: "a", nodeId: "below", label: "下方节点" }] };
  const previous = fixture(), next = fixture(300);
  previous.positions.below = { x: 20, y: 200 }; previous.sizes.below = { width: 200, height: 60 };
  previous.sizes.a.height = 280;
  next.positions.below = { x: 900, y: 60 }; next.sizes.below = previous.sizes.below;
  const current = localModel.nodes.map(n => ({ id: n.id, parentId: n.parentId,
    position: previous.positions[n.id], ...previous.sizes[n.id] }));
  const opened = preserveMeasuredLayout(localModel, next, previous, current, false);
  expect(opened.positions.below).toEqual({ x: 20, y: 400 });
  expect(opened.sizes.a).toEqual({ width: 240, height: 480 });
  const closed = preserveMeasuredLayout(localModel, previous, opened, current.map(n => ({ ...n, position: opened.positions[n.id] })), false);
  expect(closed.positions.below).toEqual(previous.positions.below);
  expect(closed.sizes.a).toEqual(previous.sizes.a);
});

it("窄伴随区增长时整体下推同一流程图，不拆散图内同排节点", () => {
  const localModel: CanvasModel = { ...model, nodes: [...model.nodes,
    ...["left", "right"].map(id => ({ id, kind: "graph" as const, parentId: "a", elementId: "diagram", nodeId: id, label: id }))] };
  const previous = fixture(), next = fixture(300);
  previous.sizes.aChild.width = next.sizes.aChild.width = 100;
  for (const [id, x] of [["left", 20], ["right", 300]] as const) {
    previous.positions[id] = next.positions[id] = { x, y: 200 };
    previous.sizes[id] = next.sizes[id] = { width: 220, height: 60 };
  }
  const current = localModel.nodes.map(n => ({ id: n.id, parentId: n.parentId,
    position: previous.positions[n.id], ...previous.sizes[n.id] }));
  const opened = preserveMeasuredLayout(localModel, next, previous, current, false);
  expect(opened.positions.left).toEqual({ x: 20, y: 400 });
  expect(opened.positions.right).toEqual({ x: 300, y: 400 });
});
