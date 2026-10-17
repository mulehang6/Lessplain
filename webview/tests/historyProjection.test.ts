import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { Spec } from "@json-render/core";
import { wrapRootContent } from "../src/components/WorkspaceGeometry";
import { buildCanvasModel, graphNodeId } from "../src/graph/canvasModel";

const graph = { type: "Graph", props: { selectionPath: "/selected", nodes: [
  { id: "read", label: "查缓存", source: "code" }, { id: "return", label: "返回" },
], edges: [{ id: "hit", source: "read", target: "return", kind: "data_flow" }] } };

describe("历史布局投影", () => {
  it("实际Redis修订保留节点关系，按动作分开锚点且没有重复前言", () => {
    const document = JSON.parse(readFileSync(new URL("../../docs/examples/redis-history.view.json", import.meta.url), "utf8"));
    const model = buildCanvasModel(document.spec);
    expect(model.groups).toHaveLength(3);
    expect(model.nodes.filter(node => node.kind === "graph")).toHaveLength(16);
    expect(model.edges).toHaveLength(13);
    expect(model.nodes.filter(node => node.kind === "companion")).toHaveLength(0);
    const request = model.nodes.find(node => node.nodeId === "hot-request")!;
    const hit = model.nodes.find(node => node.nodeId === "hot-hit")!;
    expect(request.source).not.toBe(hit.source);
    expect(document.sources[request.source!].text).toBe("public List<SideBarDTO> queryHomeSidebarList() {");
    expect(document.sources[hit.source!].text).toMatch(/^@Cacheable\(/);
    expect(document.sources[hit.source!].text).not.toContain("return list");
    expect(model.nodes.find(node => node.nodeId === "hot-hit")?.outcome).toBe("success");
    for (const ids of [["tag-read", "tag-hit", "tag-db", "tag-fill"], ["rank-query", "rank-result"]]) {
      expect(new Set(ids.map(id => model.nodes.find(node => node.nodeId === id)!.source)).size).toBe(ids.length);
    }
    for (const node of model.nodes.filter(node => node.kind === "graph")) expect(document.sources[node.source!]).toBeDefined();
    expect(new Set(model.nodes.filter(node => node.kind === "graph").map(node => node.nodeId)).size).toBe(16);
  });
  it("嵌套普通容器中的图进入画布，组件、状态、原始ID和记录保持不变", () => {
    const spec: Spec = { root: "root", state: { selected: "read" }, elements: {
      root: { type: "Stack", props: {}, children: ["text", "grid"] },
      text: { type: "Text", props: { text: "旧前言" } },
      grid: { type: "Grid", props: {}, children: ["graph"] }, graph,
    } };
    const before = JSON.stringify(spec), projected = wrapRootContent(spec, "缓存");
    const model = buildCanvasModel(projected);
    expect(model.nodes.filter(node => node.kind === "graph")).toHaveLength(2);
    expect(model.nodes.some(node => node.id === graphNodeId("graph", "read") && node.source === "code")).toBe(true);
    expect(model.edges).toHaveLength(1);
    expect(model.graphProps.graph.selectionPath).toBe("/selected");
    expect(model.nodes.filter(node => node.kind === "companion")).toHaveLength(1);
    expect(projected.elements.root.children).toEqual(["text"]);
    expect(projected.state).toBe(spec.state);
    expect(JSON.stringify(spec)).toBe(before);
  });
  it("已有分组与跨组关系从旧容器提取时保留", () => {
    const spec: Spec = { root: "wrapper", elements: {
      wrapper: { type: "Stack", props: {}, children: ["board"] },
      board: { type: "Board", props: { edges: [{ id: "between", source: { elementId: "a", nodeId: "read" }, target: { elementId: "b", nodeId: "read" }, kind: "call" }] }, children: ["c1", "c2"] },
      c1: { type: "Cluster", props: { title: "入口" }, slots: { diagram: ["a"] } },
      c2: { type: "Cluster", props: { title: "缓存" }, slots: { diagram: ["b"] } }, a: graph,
      b: { ...graph, props: { ...graph.props, selectionPath: "/other" } },
    } };
    const projected = wrapRootContent(spec, "原题"), model = buildCanvasModel(projected);
    expect(model.groups.map(group => [group.id, group.title])).toEqual([["c1", "入口"], ["c2", "缓存"]]);
    expect(model.edges).toHaveLength(3);
    expect(projected.elements.c1).toBe(spec.elements.c1);
  });
  it("Redis历史容器结构保留全部16个节点、关系及源码引用", () => {
    const document = JSON.parse(readFileSync(new URL("../../docs/examples/redis-cache-ranking.view.json", import.meta.url), "utf8"));
    const ids = ["hot-graph", "tag-graph", "rank-graph"];
    const spec: Spec = { ...document.spec, root: "old-stack", elements: {
      ...document.spec.elements, "old-stack": { type: "Stack", props: {}, children: ids },
    } };
    const model = buildCanvasModel(wrapRootContent(spec, document.title));
    expect(model.nodes.filter(node => node.kind === "graph")).toHaveLength(16);
    expect(model.edges).toHaveLength(ids.reduce((sum, id) => sum + spec.elements[id].props.edges.length, 0));
    expect(model.nodes.filter(node => node.kind === "graph").map(node => node.source).filter(Boolean))
      .toEqual(ids.flatMap(id => spec.elements[id].props.nodes.map((node: { source?: string }) => node.source).filter(Boolean)));
  });
  it("坏图、坏端点和未进入画布的图明确报错", () => {
    const invalid = { root: "g", elements: { g: { type: "Graph", props: { nodes: [], edges: [] } } } };
    expect(() => buildCanvasModel(wrapRootContent(invalid, "坏图"))).toThrow("图无法显示：g");
    const endpoints = { root: "g", elements: { g: { ...graph, props: { ...graph.props, edges: [{ id: "bad", source: "read", target: "absent", kind: "call" }] } } } };
    expect(() => buildCanvasModel(wrapRootContent(endpoints, "坏边"))).toThrow("端点不存在");
    expect(() => buildCanvasModel({ root: "c", elements: { c: { type: "Cluster", props: { title: "组" }, slots: { companion: ["g"] } }, g: graph } })).toThrow("图未进入 diagram");
  });
});
