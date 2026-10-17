import { describe, expect, it } from "vitest";
import { pointerSegments, readPointer, updatePointer } from "../src/runtime/pointer";

describe("Lesson JSON Pointer state bridge", () => {
  it("decodes escaped object keys and reads the current selection", () => {
    const lesson = { graphs: { "cache/a~b": { selectedNode: "warm" } } };
    expect(readPointer(lesson, "/graphs/cache~1a~0b/selectedNode")).toBe("warm");
  });

  it("rejects malformed pointers and never writes the document root", () => {
    expect(pointerSegments("graphs/main/selectedNode")).toBeUndefined();
    expect(pointerSegments("/graphs/bad~2key/selectedNode")).toBeUndefined();
    expect(updatePointer({ value: 1 }, "", { value: 2 })).toBeUndefined();
  });

  it("updates immutably and reports same-value selections as no-ops", () => {
    const previous = { graphs: { main: { selectedNode: "start" } } };
    const updated = updatePointer(previous, "/graphs/main/selectedNode", "next");
    expect(updated).toEqual({ value: { graphs: { main: { selectedNode: "next" } } }, changed: true });
    expect(previous.graphs.main.selectedNode).toBe("start");
    expect(updatePointer(previous, "/graphs/main/selectedNode", "start")).toEqual({ value: previous, changed: false });
  });

  it("creates missing object branches for a graph default path", () => {
    const updated = updatePointer({}, "/graphs/graph~1one/selectedNode", "entry");
    expect(updated).toEqual({ value: { graphs: { "graph/one": { selectedNode: "entry" } } }, changed: true });
  });
});
