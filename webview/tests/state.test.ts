import { describe, expect, it } from "vitest";
import { createStore } from "zustand/vanilla";
import { zustandStateStore } from "@json-render/zustand";
import { catalog } from "../src/catalog";

describe("官方状态适配器", () => {
  it("双向更新同一 slice，不改变界面选择", () => {
    const app = createStore(() => ({ selected: "view:one", lesson: { hit: false, value: 7 } }));
    const adapter = zustandStateStore({ store: app, selector: s => s.lesson, updater: (lesson, store) => store.setState({ lesson: lesson as typeof app.getState extends () => infer S ? S extends { lesson: infer L } ? L : never : never }) });
    adapter.set("/hit", true);
    expect(app.getState().lesson.hit).toBe(true);
    expect(app.getState().selected).toBe("view:one");
    app.setState({ lesson: { hit: false, value: 8 } });
    expect(adapter.get("/value")).toBe(8);
  });
  it("catalog 同时包含标准组件与源码教学组件", () => {
    expect(Object.keys(catalog.data.components)).toContain("Card");
    expect(Object.keys(catalog.data.components)).toContain("Graph");
    expect(Object.keys(catalog.data.components)).toContain("Demo");
    const parsed = catalog.data.components.Graph.props.safeParse({ nodes: [{ id: "tag-id", label: "示例记录 ID" }, { id: "name", label: "标签名称" }], edges: [] });
    expect(parsed.success).toBe(true);
  });
});
