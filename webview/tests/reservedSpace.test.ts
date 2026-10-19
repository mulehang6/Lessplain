import { describe, expect, it } from "vitest";
import { ReservedSpace } from "../src/graph/reservedSpace";

describe("显式折叠的预留空间", () => {
  it("普通比较不收缩，折叠组合之间不借用大框", () => {
    const space = new ReservedSpace();
    expect(space.measure("a:open,b:closed", 400)).toBe(400);
    expect(space.measure("a:open,b:closed", 200)).toBe(400);
    expect(space.measure("a:closed,b:closed", 80)).toBe(80);
    expect(space.measure("a:closed,b:open", 160)).toBe(160);
    expect(space.measure("a:open,b:closed", 220)).toBe(400);
  });
  it("缓存有上限，内容重建从自然高度重新测量", () => {
    const space = new ReservedSpace();
    space.measure("old", 1000);
    for (let index = 0; index < 32; index++) space.measure(String(index), 20);
    expect(space.measure("old", 40)).toBe(40);
    expect(new ReservedSpace().measure("old", 12)).toBe(12);
  });
});
