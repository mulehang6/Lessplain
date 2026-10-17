import { describe, expect, it } from "vitest";
import { buildCanvasModel, graphNodeId } from "../src/graph/canvasModel";
import { edgeLabelPoint, nodeBoxes, repairSections, sectionPoints, segmentHitsBox, type NodeGeometry } from "../src/graph/edgeRepair";
import type { Layout } from "../src/graph/layout";

const model = buildCanvasModel({ root: "c", elements: {
  c: { type: "Cluster", props: { title: "路径" }, slots: { diagram: ["g"] } },
  g: { type: "Graph", props: { nodes: [{ id: "a", label: "起点" }, { id: "b", label: "终点" }, { id: "obstacle", label: "障碍" }], edges: [{ id: "ab", source: "a", target: "b", kind: "call" }] } },
} });
const a = graphNodeId("g", "a"), b = graphNodeId("g", "b"), obstacle = graphNodeId("g", "obstacle"), edge = model.edges[0].id, group = model.groups[0].nodeId;
const layout: Layout = { positions: { [group]: { x: 0, y: 0 }, [a]: { x: 0, y: 0 }, [b]: { x: 300, y: 0 }, [obstacle]: { x: 160, y: 140 } },
  sizes: { [group]: { width: 500, height: 300 }, [a]: { width: 100, height: 60 }, [b]: { width: 100, height: 60 }, [obstacle]: { width: 80, height: 60 } },
  sections: { [edge]: [{ id: "ab", startPoint: { x: 100, y: 30 }, endPoint: { x: 300, y: 30 } }] } };
const nodes = (): NodeGeometry[] => model.nodes.map(n => ({ id: n.id, parentId: n.parentId, position: { ...layout.positions[n.id] }, ...layout.sizes[n.id] }));
function clear(points: { x: number; y: number }[], geometry: NodeGeometry[]) {
  const boxes = nodeBoxes(geometry);
  for (let i = 1; i < points.length; i++) for (const id of [a, b, obstacle]) expect(segmentHitsBox(points[i - 1], points[i], boxes.get(id)!)).toBe(false);
}
describe("旧版局部修线", () => {
  it("无变化保留边对象，整体移动分组同步移动端点、折点与标签", () => {
    expect(repairSections(model, layout, nodes(), false)[edge]).toBe(layout.sections[edge]);
    const geometry = nodes(); geometry.find(n => n.id === group)!.position = { x: 70, y: 45 };
    const repaired = repairSections(model, layout, geometry, false)[edge];
    expect(sectionPoints(repaired)).toEqual([{ x: 170, y: 75 }, { x: 370, y: 75 }]);
    expect(edgeLabelPoint(repaired)).toEqual({ x: 270, y: 75 });
  });
  it("移动无关障碍也修复被遮挡的边，结果确定且不移动节点", () => {
    const geometry = nodes(); geometry.find(n => n.id === obstacle)!.position = { x: 160, y: 0 };
    const original = JSON.stringify(geometry);
    const repaired = repairSections(model, layout, geometry, false);
    clear(sectionPoints(repaired[edge]), geometry);
    expect(repairSections(model, layout, geometry, false)).toEqual(repaired);
    expect(JSON.stringify(geometry)).toBe(original);
  });
  it("单独移动端点不拼接旧折点，路径始终连接当前端点", () => {
    const geometry = nodes(); geometry.find(n => n.id === a)!.position.y = 80;
    const repaired = repairSections(model, layout, geometry, false)[edge];
    expect(repaired[0].startPoint).toEqual({ x: 100, y: 110 });
    expect(repaired[0].endPoint).toEqual({ x: 300, y: 30 });
    clear(sectionPoints(repaired), geometry);
  });
  it("跨组移动只改变一端时修复路径，不平移整条边", () => {
    const crossModel = { ...model, nodes: model.nodes.map(n => n.id === b ? { ...n, parentId: undefined } : n) };
    const geometry = nodes().map(n => n.id === b ? { ...n, parentId: undefined } : n);
    geometry.find(n => n.id === group)!.position.y = 70;
    const repaired = repairSections(crossModel, layout, geometry, false)[edge];
    expect(repaired[0].startPoint).toEqual({ x: 100, y: 100 });
    expect(repaired[0].endPoint).toEqual({ x: 300, y: 30 });
    clear(sectionPoints(repaired), geometry);
  });
  it("节点重叠仍产生有限确定路径，保留边ID", () => {
    const geometry = nodes(); geometry.find(n => n.id === b)!.position = { x: 0, y: 0 };
    const repaired = repairSections(model, layout, geometry, false);
    expect(Object.keys(repaired)).toEqual([edge]);
    expect(sectionPoints(repaired[edge]).every(p => Number.isFinite(p.x) && Number.isFinite(p.y))).toBe(true);
  });
});
