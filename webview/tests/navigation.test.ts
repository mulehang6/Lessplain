import { describe, expect, it } from "vitest";
import type { GraphProps } from "../src/catalog";
import type { Snapshot, View } from "../src/runtime/types";
import { branchNodes, cyclePreview, startingNode, stepChoices, upstreamEdges, viewLayers } from "../src/graph/navigation";
import { scopedQuestions } from "../src/runtime/questions";

const graph: GraphProps = { nodes: [{ id: "registered", label: "注册" }, { id: "tool", label: "工具", roles: ["start"] }, { id: "store", label: "存储" }],
  edges: [{ id: "before", source: "registered", target: "tool", kind: "call" }, { id: "after", source: "tool", target: "store", kind: "call" }] };
const view = (ref: string, parentRef?: string) => ({ ref, parentRef, document: { question: ref } } as View);
describe("旧图表的路线与理解层级", () => {
  it("start 是首次焦点，不是上一步边界；无 start 时选无入边节点", () => {
    expect(startingNode(graph)).toBe("tool");
    expect(stepChoices(graph, "tool", "previous").map(edge => edge.source)).toEqual(["registered"]);
    expect(startingNode({ ...graph, nodes: graph.nodes.map(node => ({ ...node, roles: [] })) })).toBe("registered");
  });
  it("仅可导航关系参与步骤；平行关系和自环不制造分支", () => {
    const branch = { ...graph, edges: [...graph.edges, { id: "another", source: "tool", target: "store", kind: "dependency" as const }, { id: "self", source: "tool", target: "tool", kind: "call" as const }] };
    expect(stepChoices(branch, "tool", "next").map(edge => edge.id)).toEqual(["after"]);
    expect(stepChoices(branch, "store", "next")).toEqual([]);
    expect(branchNodes(branch).size).toBe(0);
    const realBranch = { ...branch, edges: [...branch.edges, { id: "choice", source: "tool", target: "registered", kind: "data_flow" as const }] };
    expect([...branchNodes(realBranch)]).toEqual(["tool"]);
    expect(cyclePreview(realBranch.edges, "after", 1)).toBe("another");
    expect(cyclePreview(graph.edges, "before", -1)).toBe("after");
    expect(cyclePreview([], undefined, 1)).toBeUndefined();
  });
  it("上游高亮遍历循环时仍会结束", () => {
    expect([...upstreamEdges({ ...graph, edges: [...graph.edges, { id: "cycle", source: "store", target: "registered", kind: "call" }] }, "store")].sort()).toEqual(["after", "before", "cycle"]);
  });
  it("层级与复制从追问回到理解根，包含同根兄弟，排除其他历史根", () => {
    const views = [view("root"), view("a", "root"), view("grandchild", "a"), view("b", "root"), view("other")];
    expect(viewLayers(views, "grandchild").map(item => [item.view.ref, item.depth])).toEqual([["root", 0], ["a", 1], ["grandchild", 2], ["b", 1]]);
    const snapshot = { views, notes: [{ ref: "n1", ownerRef: "a" }, { ref: "n2", ownerRef: "b" }, { ref: "n3", ownerRef: "other" }, { ref: "source" }], browse: {} } as Snapshot;
    expect(scopedQuestions(snapshot, "grandchild").map(note => note.ref)).toEqual(["n1", "n2", "source"]);
  });
});
