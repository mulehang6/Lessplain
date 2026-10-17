import type { Snapshot } from "./types";
declare global {
  interface Window {
    __lessplainQuery?: (raw: string) => void;
    __lessplainReceive?: (message: BridgeMessage) => void;
    __lessplainBoot?: { ref?: string; title?: string; hostId?: string; host?: "toolwindow" | "editor"; diagnostics?: boolean };
    __lessplainReceiveChunk?: (packet: string, index: number, total: number, chunk: string) => void;
  }
}
export type NavigationRequest = { id: string; direction: "previous" | "next"; viewRef: string; revision: number; elementId: string; nodeId: string };
type BridgeMessage = { id?: string; result?: unknown; error?: string; snapshot?: Snapshot; sequence?: number; theme?: Record<string, string>; select?: string; showHistory?: boolean; navigationRequest?: NavigationRequest };
const pending = new Map<string, { resolve(value: unknown): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> }>();
let onMessage: (message: BridgeMessage) => void = () => {};
export function bindBridge(listener: typeof onMessage) {
  onMessage = listener;
  const packets = new Map<string, { chunks: string[]; received: number; length: number; time: number }>();
  window.__lessplainReceiveChunk = (packet, index, total, chunk) => {
    const now = Date.now();
    packets.forEach((value, key) => { if (now - value.time > 15000) packets.delete(key); });
    if (total < 1 || total > 512 || index < 0 || index >= total || chunk.length > 64000) return;
    if (!packets.has(packet)) {
      if (packets.size >= 4) return;
      packets.set(packet, { chunks: new Array(total), received: 0, length: 0, time: now });
    }
    const value = packets.get(packet)!;
    if (value.chunks.length !== total || value.chunks[index] !== undefined) return;
    value.chunks[index] = chunk; value.received++; value.length += chunk.length;
    if (value.length > 32_000_000) { packets.delete(packet); return; }
    if (value.received === total) {
      packets.delete(packet);
      try { window.__lessplainReceive?.(JSON.parse(value.chunks.join(""))); } catch { onMessage({ error: "页面消息解析失败" }); }
    }
  };
  window.__lessplainReceive = message => {
    if (message.id) {
      const waiter = pending.get(message.id);
      if (waiter) {
        clearTimeout(waiter.timer); pending.delete(message.id);
        if (message.error) waiter.reject(new Error(message.error)); else waiter.resolve(message.result);
      }
    } else {
      if (message.navigationRequest) window.dispatchEvent(new CustomEvent("lessplain:navigate", { detail: message.navigationRequest }));
      onMessage(message);
    }
  };
}
export function call<T = unknown>(method: string, params: unknown = {}): Promise<T> {
  const send = window.__lessplainQuery;
  if (!send) return Promise.reject(new Error("IDE 连接尚未就绪"));
  if (pending.size >= 64) return Promise.reject(new Error("操作过多，请稍后重试"));
  const id = crypto.randomUUID();
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error("IDE 操作超时")); }, 15000);
    pending.set(id, { resolve: value => resolve(value as T), reject, timer });
    send(JSON.stringify({ id, method, params }));
  });
}
export function ready() {
  if (!window.__lessplainQuery) return;
  void call<{ snapshot: Snapshot; sequence?: number; ref?: string; theme: Record<string, string> }>("ready")
    .then(result => onMessage({ ...result, select: result.ref }))
    .catch(error => onMessage({ error: String(error) }));
}
window.addEventListener("lessplain-connected", ready);
