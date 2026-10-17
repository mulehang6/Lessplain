import { describe, expect, it } from "vitest";
import { mergeMeasuredSizes, retainMeasuredSizes } from "../src/graph/measurements";

describe("画布尺寸测量增量维护", () => {
  it("拖动产生的 position 变更保留同一个测量数组，不触发布局尺寸更新", () => {
    const current = [{ id: "node/a", width: 220, height: 80 }];
    const next = mergeMeasuredSizes(current, [{ type: "position", id: "node/a" }]);
    expect(next).toBe(current);
  });

  it("只在 dimensions 变化时更新对应节点，重复测量保持引用稳定", () => {
    const current = [{ id: "node/a", width: 220, height: 80 }, { id: "node/b", width: 280, height: 110 }];
    const changed = mergeMeasuredSizes(current, [{ type: "dimensions", id: "node/b", dimensions: { width: 300, height: 120 } }]);
    expect(changed).not.toBe(current);
    expect(changed).toEqual([{ id: "node/a", width: 220, height: 80 }, { id: "node/b", width: 300, height: 120 }]);
    expect(mergeMeasuredSizes(changed, [{ type: "dimensions", id: "node/b", dimensions: { width: 300, height: 120 } }])).toBe(changed);
  });

  it("结构变化时只移除失效节点测量", () => {
    const current = [{ id: "node/a", width: 220, height: 80 }, { id: "node/b", width: 280, height: 110 }];
    const retained = retainMeasuredSizes(current, new Set(["node/b", "node/c"]));
    expect(retained).toEqual([{ id: "node/b", width: 280, height: 110 }]);
    expect(retainMeasuredSizes(retained, new Set(["node/b", "node/c"]))).toBe(retained);
  });
});
