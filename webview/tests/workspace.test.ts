import { describe, expect, it } from "vitest";
import type { Spec } from "@json-render/core";
import { catalog, graphProps } from "../src/catalog";
import { wrapRootContent } from "../src/components/WorkspaceGeometry";
import { buildCanvasModel, companionNodeId, graphNodeId, groupNodeId } from "../src/graph/canvasModel";

const boardSpec = (): Spec => ({
  root: "board/main",
  elements: {
    "board/main": { type: "Board", props: { edges: [{
      id: "cross", source: { elementId: "graph/a", nodeId: "same" }, target: { elementId: "graph/b", nodeId: "same" }, kind: "dependency",
    }] }, children: ["cluster/a", "cluster/b"] },
    "cluster/a": { type: "Cluster", props: { title: "A" }, slots: { diagram: ["graph/a"], companion: ["card/a"] } },
    "cluster/b": { type: "Cluster", props: { title: "B" }, slots: { diagram: ["graph/b"] } },
    "graph/a": { type: "Graph", props: { nodes: [{ id: "same", label: "A" }, { id: "next", label: "Next" }],
      edges: [{ id: "local", source: "same", target: "next", kind: "call" }] } },
    "graph/b": { type: "Graph", props: { nodes: [{ id: "same", label: "B" }], edges: [] } },
    "card/a": { type: "Text", props: { text: "Normal companion" } },
  },
});

describe("unified Board canvas", () => {
  it("exposes Board edges and Cluster slots in the catalog", () => {
    const components = catalog.data.components as Record<string, { slots?: string[]; props?: { safeParse(value: unknown): { success: boolean } } }>;
    expect(components.Board.slots).toBeUndefined();
    expect(components.Board.props?.safeParse({ edges: [{ id: "x", source: { elementId: "g1", nodeId: "n" },
      target: { elementId: "g2", nodeId: "n" }, kind: "call" }] }).success).toBe(true);
    expect(components.Cluster.slots).toEqual(["diagram", "companion"]);
  });

  it("namespaces graph, companion and group IDs and preserves cross-Cluster topology", () => {
    const model = buildCanvasModel(boardSpec());
    const graphA = graphNodeId("graph/a", "same"), graphB = graphNodeId("graph/b", "same");
    expect(graphA).not.toBe(graphB);
    expect(model.nodes.find(node => node.id === graphA)?.parentId).toBe(groupNodeId("cluster/a"));
    expect(model.nodes.find(node => node.id === companionNodeId("card/a"))?.parentId).toBe(groupNodeId("cluster/a"));
    expect(model.edges.map(edge => edge.id)).toEqual(expect.arrayContaining([
      expect.stringContaining("graph-edge/"), expect.stringContaining("board-edge/"),
    ]));
    expect(model.edges.find(edge => edge.source === graphA && edge.target === graphB)?.sourceElementId).toBe("graph/a");
  });

  it("wraps only a non-Board root and leaves Graph identity intact", () => {
    const spec: Spec = { root: "graph/main", elements: {
      "graph/main": { type: "Graph", props: { nodes: [{ id: "start", label: "Start" }], edges: [] } },
      __workspace_cluster: { type: "Text", props: { text: "existing" } },
    } };
    const wrapped = wrapRootContent(spec, "Cache flow");
    const board = wrapped.elements[wrapped.root];
    const clusterId = board.children?.[0];
    expect(board.type).toBe("Board");
    expect(clusterId).not.toBe("__workspace_cluster");
    expect(wrapped.elements[clusterId!].slots?.diagram).toEqual(["graph/main"]);
    expect(wrapped.elements["graph/main"]).toBe(spec.elements["graph/main"]);
    expect(buildCanvasModel(wrapped).nodes.map(node => node.id)).toContain(graphNodeId("graph/main", "start"));
  });

  it("keeps selectionPath a literal valid JSON Pointer and removes per-Graph height", () => {
    const base = { nodes: [{ id: "start", label: "Start" }], edges: [] };
    expect(graphProps.safeParse({ ...base, selectionPath: "/graphs/main/selectedNode" }).success).toBe(true);
    expect(graphProps.safeParse({ ...base, selectionPath: "graphs/main" }).success).toBe(false);
    expect(graphProps.safeParse({ ...base, selectionPath: "/graphs/bad~2key" }).success).toBe(false);
    expect("height" in graphProps.shape).toBe(false);
  });
});
