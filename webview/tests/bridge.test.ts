import { describe, expect, it, vi } from "vitest";
vi.stubGlobal("window", { addEventListener: vi.fn() });
const { bindBridge } = await import("../src/runtime/bridge");
describe("分块消息", () => {
  it("乱序分块保留 Unicode，只在收齐后投递", () => {
    const received: unknown[] = [];
    bindBridge(message => received.push(message));
    const raw = JSON.stringify({ error: "源码 📘 ".repeat(12000) });
    const chunks = [raw.slice(0, 64000), raw.slice(64000)];
    window.__lessplainReceiveChunk?.("packet", 1, 2, chunks[1]);
    expect(received).toHaveLength(0);
    window.__lessplainReceiveChunk?.("packet", 0, 2, chunks[0]);
    expect(received).toEqual([JSON.parse(raw)]);
  });
  it("重复与不完整消息不会提前投递", () => {
    const received: unknown[] = [];
    bindBridge(message => received.push(message));
    window.__lessplainReceiveChunk?.("packet", 0, 2, '{"error":');
    window.__lessplainReceiveChunk?.("packet", 0, 2, '{"error":');
    expect(received).toHaveLength(0);
    window.__lessplainReceiveChunk?.("packet", 1, 2, '"bad"}');
    expect(received).toEqual([{ error: "bad" }]);
  });
});
