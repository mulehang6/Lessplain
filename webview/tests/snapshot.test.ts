import { describe, expect, it } from "vitest";
import { shareViews } from "../src/runtime/snapshot";
import type { View } from "../src/runtime/types";

const view = (ref: string, revision = 1): View => ({ ref, revision, createdAt: 1, updatedAt: 1,
  origin: { state: {} }, sources: {}, document: { question: ref, spec: { root: "t", elements: { t: { type: "Text", props: { text: ref } } } } } });

describe("宿主快照结构复用", () => {
  it("备注与浏览状态更新时复用未改版的数组和视图", () => {
    const previous = [view("root"), view("answer")];
    expect(shareViews(previous, structuredClone(previous))).toBe(previous);
  });
  it("改版、增删和顺序改变只替换对应身份", () => {
    const previous = [view("root"), view("answer")];
    const updated = shareViews(previous, [view("root", 2), view("answer"), view("new")]);
    expect(updated[0]).not.toBe(previous[0]); expect(updated[1]).toBe(previous[1]);
    expect(shareViews(previous, [view("answer")])).toEqual([previous[1]]);
    expect(shareViews(previous, [view("answer"), view("root")])[0]).toBe(previous[1]);
  });
});
