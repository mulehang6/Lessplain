import { describe, expect, it, vi } from "vitest";
vi.stubGlobal("window", { addEventListener: vi.fn() });
const { normalizeCursor, setCanvasCursor } = await import("../src/runtime/cursor");

describe("画布光标桥接", () => {
  it("只接受画布语义，不继承窗口边界 resize 光标", () => {
    expect(normalizeCursor("col-resize")).toBe("default");
    expect(normalizeCursor(undefined)).toBe("default");
    for (const value of ["default", "pointer", "text", "grab", "grabbing"]) expect(normalizeCursor(value)).toBe(value);
  });
  it("连接未就绪时不发送，也不缓存未发送的状态", () => {
    setCanvasCursor("pointer");
    const send = vi.fn();
    window.__lessplainQuery = send;
    setCanvasCursor("pointer");
    setCanvasCursor("pointer");
    expect(send).toHaveBeenCalledTimes(1);
    const request = JSON.parse(send.mock.calls[0][0]);
    expect(request.method).toBe("cursor");
    expect(request.params).toEqual({ cursor: "pointer" });
    delete window.__lessplainQuery;
  });
});
