import { describe, expect, it, vi } from "vitest";
import { notesForComponent, notesForNode, questionsForAgent } from "../src/runtime/questions";
import type { Note } from "../src/runtime/types";

vi.stubGlobal("window", { addEventListener: vi.fn() });
const { beginAnnotation, closeAnnotation, origin, updateAnnotation, useApp } = await import("../src/runtime/store");
const note = (ref: string, status: string, createdAt: number, ownerRef?: string): Note => ({
  ref, status, createdAt, ownerRef, kind: "question", text: "为什么返回 JSON？", origin: { state: {}, source: { path: "Tag.java", text: "秘密源码不应重复粘贴", line: 2, version: "test" } },
});
describe("Agent 与插件的问答往返", () => {
  it("统一入口直接显示输入，已有注释和草稿不会增加一次展开操作", () => {
    useApp.setState({ selected: "view:entry", elementId: "json", nodeId: undefined, drafts: {}, snapshot: { views: [], browse: {}, notes: [] } });
    beginAnnotation(); expect(useApp.getState().annotationMode).toBe("input");
    updateAnnotation({ text: "草稿" }); closeAnnotation(); beginAnnotation();
    expect(useApp.getState().annotationMode).toBe("input");
    closeAnnotation(useApp.getState().annotation);
    useApp.setState({ snapshot: { views: [], browse: {}, notes: [{ ...note("note:entry", "pending", 1, "view:entry"), origin: { viewRef: "view:entry", elementId: "json", state: {} } }] } });
    beginAnnotation(); expect(useApp.getState().annotationMode).toBe("input");
    useApp.setState({ nodeId: "another" }); beginAnnotation(); expect(useApp.getState().annotationMode).toBe("input");
  });
  it("删除或回答保留独立的新注释草稿", () => {
    useApp.setState({ selected: "view:entry", elementId: "json", nodeId: undefined, drafts: {}, snapshot: { views: [], browse: {}, notes: [] } });
    beginAnnotation(); updateAnnotation({ text: "输入中的新问题" });
    const key = useApp.getState().annotation!;
    useApp.getState().receive({ views: [], browse: {}, notes: [] });
    expect(useApp.getState().drafts[key].text).toBe("输入中的新问题");
  });
  it("批量复制跨页面和独立源码的 pending 原文，不清除状态，不重复源码或技术说明", () => {
    const notes = [note("note:b@2", "pending", 2, "view:b"), note("note:a@1", "pending", 1, "view:a"),
      note("note:source@1", "pending", 3), note("note:done@1", "answered", 4), note("note:cancel@1", "cancelled", 5)];
    const output = questionsForAgent(notes);
    expect(output).toBe("请处理以下待答问题，通过 Lessplain 提交对应回复：\n\n1. 为什么返回 JSON？\n   引用：note:a@1\n\n2. 为什么返回 JSON？\n   引用：note:b@2\n\n3. 为什么返回 JSON？\n   引用：note:source@1");
    expect(output).not.toContain("秘密源码");
    expect(notes.filter(n => n.status === "pending")).toHaveLength(3);
    expect(questionsForAgent([note("note:done@1", "answered", 1)])).toBe("");
  });
  it("注释在入口固定来源和演示状态，切换阅读对象不会改变草稿；关闭后可恢复", () => {
    useApp.setState({ selected: "view:a", elementId: "json", lesson: { hit: true }, drafts: {}, annotation: undefined });
    beginAnnotation(); updateAnnotation({ text: "这个 ID 是什么？" });
    const key = useApp.getState().annotation!;
    useApp.setState({ selected: "view:b", elementId: "demo", lesson: { hit: false } });
    expect(useApp.getState().drafts[key].origin).toMatchObject({ viewRef: "view:a", elementId: "json", state: { hit: true } });
    closeAnnotation();
    beginAnnotation({ viewRef: "view:a", elementId: "json", state: { hit: false } });
    expect(useApp.getState().drafts[key].text).toBe("这个 ID 是什么？");
    expect(useApp.getState().drafts[key].origin.state).toEqual({ hit: true });
    expect(origin()).toMatchObject({ viewRef: "view:b", elementId: "demo" });
    closeAnnotation(key);
    expect(useApp.getState().drafts[key]).toBeUndefined();
  });
  it("节点锚点不冒充编辑器选区，来源类型也隔离草稿", () => {
    const source = { path: "Tag.java", text: "return tag;", line: 2, version: "fixture" };
    const spec = { root: "graph", elements: { graph: { type: "Graph", props: {
      nodes: [{ id: "tag", label: "读取标签", source: "tag-source" }],
    } } } };
    useApp.setState({ selected: "view:node", elementId: "graph", nodeId: "tag", sourceId: "tag-source", lesson: {}, drafts: {},
      snapshot: { notes: [], browse: {}, views: [{ ref: "view:node", revision: 1, createdAt: 1, updatedAt: 1, origin: { state: {} }, sources: { "tag-source": source },
        document: { question: "标签", demos: {}, spec } }] } });
    expect(origin()).toMatchObject({ entry: "node", nodeId: "tag", nodeLabel: "读取标签" });
    expect(origin()).not.toHaveProperty("source");
    beginAnnotation();
    const nodeKey = useApp.getState().annotation!;
    beginAnnotation({ ...origin(), entry: "sourceSelection", source });
    expect(useApp.getState().annotation).not.toBe(nodeKey);
    expect(useApp.getState().drafts[nodeKey].origin.source).toBeUndefined();
  });
  it("节点待答列表只收集同一页面、元素和节点的节点问题与源码选区", () => {
    const source = { path: "ChatRunController.kt", text: "with", line: 87, version: "v1", selectionRange: { startOffset: 3274, endOffset: 3278 } };
    const at = (ref: string, entry: "node" | "sourceSelection", patch: Record<string, unknown> = {}): Note => ({
      ...note(ref, "pending", 1, "view:graph"),
      origin: { entry, viewRef: "view:graph", elementId: "sendGraph", nodeId: "send", state: {}, ...patch },
    });
    const notes = [
      at("note:node", "node"),
      at("note:source", "sourceSelection", { source }),
      { ...at("note:answered-source", "sourceSelection", { source }), status: "answered" },
      at("note:other-node", "sourceSelection", { nodeId: "receive" }),
      at("note:other-element", "sourceSelection", { elementId: "otherGraph" }),
      at("note:other-view", "sourceSelection", { viewRef: "view:other" }),
      at("note:unowned-same-element", "sourceSelection", { nodeId: undefined }),
      at("note:unowned-source", "sourceSelection", { nodeId: undefined, elementId: undefined }),
    ];

    expect(notesForNode(notes, { viewRef: "view:graph", elementId: "sendGraph", nodeId: "send" }).map(item => item.ref))
      .toEqual(["note:node", "note:source", "note:answered-source"]);
    expect(notesForNode(notes, { viewRef: "view:graph", elementId: "sendGraph", nodeId: undefined })).toEqual([]);
  });
  it("组件列表不把节点源码选区或无归属源码选区算作组件注释", () => {
    const component = (ref: string, entry: Note["origin"]["entry"], patch: Record<string, unknown> = {}): Note => ({
      ...note(ref, "pending", 1, "view:graph"),
      origin: { entry, viewRef: "view:graph", elementId: "sendGraph", state: {}, ...patch },
    });
    const notes = [
      component("note:component", "component"),
      component("note:node-source", "sourceSelection", { nodeId: "send", source: { path: "x.kt", text: "with", line: 87, version: "v1" } }),
      component("note:unowned-source", "sourceSelection"),
      component("note:other-component", "component", { elementId: "otherGraph" }),
    ];

    expect(notesForComponent(notes, "view:graph", "sendGraph").map(item => item.ref)).toEqual(["note:component"]);
  });
});
