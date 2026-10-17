import { describe, expect, it } from "vitest";
import { buildCanvasModel, graphNodeId } from "../src/graph/canvasModel";
import { stepChoices, upstreamEdges } from "../src/graph/navigation";

describe("全局画布拓扑导航", () => {
  const model = buildCanvasModel({ root: "board", elements: {
    board: { type: "Board", props: { edges: [{ id: "between", source: { elementId: "graph/a", nodeId: "next" },
      target: { elementId: "graph/b", nodeId: "start" }, kind: "data_flow" }] }, children: ["cluster/a", "cluster/b"] },
    "cluster/a": { type: "Cluster", props: { title: "A" }, slots: { diagram: ["graph/a"] } },
    "cluster/b": { type: "Cluster", props: { title: "B" }, slots: { diagram: ["graph/b"] } },
    "graph/a": { type: "Graph", props: { nodes: [{ id: "start", label: "A start" }, { id: "next", label: "A next" }],
      edges: [{ id: "local", source: "start", target: "next", kind: "call" }] } },
    "graph/b": { type: "Graph", props: { nodes: [{ id: "start", label: "B start" }], edges: [] } },
  } });

  it("previous/next traverse both local and cross-Cluster edges by namespaced node ID", () => {
    const aStart = graphNodeId("graph/a", "start"), aNext = graphNodeId("graph/a", "next");
    const bStart = graphNodeId("graph/b", "start");
    expect(stepChoices(model, aStart, "next").map(edge => edge.id)).toEqual([expect.stringContaining("graph-edge/")]);
    expect(stepChoices(model, aNext, "next").map(edge => edge.id)).toEqual([expect.stringContaining("board-edge/")]);
    expect(stepChoices(model, bStart, "previous").map(edge => edge.id)).toEqual([expect.stringContaining("board-edge/")]);
    expect([...upstreamEdges(model, bStart)]).toEqual(expect.arrayContaining([
      expect.stringContaining("graph-edge/"), expect.stringContaining("board-edge/"),
    ]));
  });
});
