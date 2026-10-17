import { describe, expect, it, vi } from "vitest";
const runtime = vi.hoisted(() => ({
  tasks: [] as { resolve(value: unknown): void; reject(error: Error): void }[],
  terminated: vi.fn(), creates: vi.fn(),
}));
vi.mock("elkjs/lib/elk-api", () => ({ default: class {
  constructor() { runtime.creates(); }
  layout() { return new Promise((resolve, reject) => { runtime.tasks.push({ resolve, reject }); }); }
  terminateWorker() { runtime.terminated(); }
} }));
const { LayoutQueue, layoutInput, readLayout } = await import("../src/graph/layout");
const { buildCanvasModel } = await import("../src/graph/canvasModel");
const flush = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); };
describe("Worker 请求管理", () => {
  it("Worker 构造失败报告错误且后续请求仍可恢复", async () => {
    runtime.tasks.length = 0;
    runtime.creates.mockImplementationOnce(() => { throw new Error("Worker unavailable"); });
    const queue = new LayoutQueue(), success = vi.fn(), fail = vi.fn();
    queue.request({ id: "a" }, success, fail); await flush();
    expect(fail).toHaveBeenCalledWith(expect.objectContaining({ message: "Worker unavailable" }));
    queue.request({ id: "b" }, success, fail); await flush();
    runtime.tasks[0].resolve({ id: "b" }); await flush();
    expect(success).toHaveBeenCalledOnce();
    queue.dispose();
  });
  it("新请求仍在延迟期间，输入失效即丢弃旧成功或失败结果", async () => {
    runtime.tasks.length = 0;
    const queue = new LayoutQueue(), old = vi.fn(), fail = vi.fn(), current = vi.fn();
    queue.request({ id: "old" }, old, fail);
    await flush();
    queue.invalidate();
    runtime.tasks[0].resolve({ id: "old", children: [] }); await flush();
    expect(old).not.toHaveBeenCalled();
    queue.request({ id: "later" }, current, fail);
    await flush();
    runtime.tasks[1].resolve({ id: "later", children: [] }); await flush();
    expect(current).toHaveBeenCalledOnce();
    queue.request({ id: "failure" }, old, fail); await flush(); queue.invalidate();
    runtime.tasks[2].reject(new Error("过期失败")); await flush();
    expect(fail).not.toHaveBeenCalled(); queue.dispose();
  });
  it("局部边和声明container的边均转成全局坐标，节点仍保留parent-relative位置", () => {
    const section = { id: "segment", startPoint: { x: 20, y: 50 }, endPoint: { x: 70, y: 80 }, bendPoints: [{ x: 20, y: 80 }] };
    const result = readLayout({ id: "root", children: [{ id: "group", x: 100, y: 200,
      children: [{ id: "a", x: 20, y: 50 }],
      edges: [{ id: "nested", sources: ["a"], targets: ["a"], sections: [section] }],
    }], edges: [{ id: "declared", sources: ["a"], targets: ["a"], container: "group", sections: [section] }] });
    expect(result.positions.a).toEqual({ x: 20, y: 50 });
    expect(result.sections.nested).toEqual(result.sections.declared);
    expect(result.sections.declared[0]).toMatchObject({ startPoint: { x: 120, y: 250 }, endPoint: { x: 170, y: 280 }, bendPoints: [{ x: 120, y: 280 }] });
    expect(section.startPoint).toEqual({ x: 20, y: 50 });
  });
  it("旧 Worker 永不返回也不能阻塞最新请求，同一轮新输入合并", async () => {
    runtime.tasks.length = 0;
    const queue = new LayoutQueue(), first = vi.fn(), last = vi.fn();
    queue.request({ id: "a" }, first, vi.fn());
    await flush();
    queue.request({ id: "b" }, vi.fn(), vi.fn());
    queue.request({ id: "c" }, last, vi.fn());
    await flush();
    expect(first).not.toHaveBeenCalled();
    expect(runtime.tasks).toHaveLength(2);
    runtime.tasks[1].resolve({ id: "c", children: [{ id: "node", x: 7, y: 8 }], edges: [] });
    await flush();
    expect(last).toHaveBeenCalledWith({ positions: { node: { x: 7, y: 8 } }, sizes: {}, sections: {} });
    runtime.tasks[0].resolve({ id: "a", children: [{ id: "stale" }] }); await flush();
    expect(first).not.toHaveBeenCalled();
    expect(last).toHaveBeenCalledOnce();
    queue.dispose();
  });
  it("builds one compound ELK graph with measured group bounds and global cross-group edges", () => {
    const model = buildCanvasModel({ root: "board", elements: {
      board: { type: "Board", props: { edges: [{ id: "cross", source: { elementId: "ga", nodeId: "a" },
        target: { elementId: "gb", nodeId: "b" }, kind: "call" }] }, children: ["ca", "cb"] },
      ca: { type: "Cluster", props: { title: "A" }, slots: { diagram: ["ga"] } },
      cb: { type: "Cluster", props: { title: "B" }, slots: { diagram: ["gb"] } },
      ga: { type: "Graph", props: { nodes: [{ id: "a", label: "A" }], edges: [] } },
      gb: { type: "Graph", props: { nodes: [{ id: "b", label: "B" }], edges: [] } },
    } });
    const input = layoutInput(model, model.nodes.map(node => ({ id: node.id, width: 220, height: 80 })), "RIGHT");
    expect(input.layoutOptions?.["elk.hierarchyHandling"]).toBe("INCLUDE_CHILDREN");
    expect(input.children?.[0].children?.[0].id).toContain("graph-node/");
    expect(input.edges).toHaveLength(1);
    expect(input.edges?.[0].sources?.[0]).toContain("graph-node/");
    expect(input.children?.[0].width).toBeUndefined();
    // 已算出的父框不能成为下次最小尺寸，否则换方向后只能变大、无法收紧。
    const expandedParents = model.nodes.map(node => ({ id: node.id, width: node.kind === "group" ? 5000 : 220, height: node.kind === "group" ? 5000 : 80 }));
    expect(layoutInput(model, expandedParents, "RIGHT")).toEqual(input);
  });
  it("失败不覆写布局，关闭后的失败不重建 Worker", async () => {
    runtime.tasks.length = 0;
    const queue = new LayoutQueue(), success = vi.fn(), fail = vi.fn();
    queue.request({ id: "a" }, success, fail);
    await flush();
    runtime.tasks[0].reject(new Error("failed"));
    await flush();
    expect(success).not.toHaveBeenCalled(); expect(fail).toHaveBeenCalledOnce();
    queue.request({ id: "b" }, success, fail);
    await flush();
    const before = runtime.creates.mock.calls.length;
    queue.dispose(); runtime.tasks[1].reject(new Error("closed"));
    await flush();
    expect(runtime.creates).toHaveBeenCalledTimes(before);
    expect(fail).toHaveBeenCalledOnce();
  });
  it("超时只报告一次，下一次请求创建 Worker 并可成功", async () => {
    vi.useFakeTimers();
    runtime.tasks.length = 0;
    const queue = new LayoutQueue(), success = vi.fn(), fail = vi.fn();
    try {
      queue.request({ id: "slow" }, success, fail); await flush();
      await vi.advanceTimersByTimeAsync(8000);
      expect(fail).toHaveBeenCalledWith(expect.objectContaining({ message: "布局超时，保留上次位置" }));
      queue.request({ id: "retry" }, success, fail); await flush();
      runtime.tasks[1].resolve({ id: "retry" }); await flush();
      expect(success).toHaveBeenCalledOnce();
      runtime.tasks[0].reject(new Error("迟到的错误")); await flush();
      expect(fail).toHaveBeenCalledOnce();
    } finally { queue.dispose(); vi.useRealTimers(); }
  });
});
