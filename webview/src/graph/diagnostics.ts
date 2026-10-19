type CanvasEvent = { time: number; kind: string; [key: string]: string | number };
const events: CanvasEvent[] = [];
let installed = false;
let sequence = 0;
type DiagnosticBatch = { visible: boolean; events: CanvasEvent[] };
let sendBatch: ((batch: DiagnosticBatch) => Promise<unknown>) | undefined;

/** 浏览器入口提供 bridge；布局和纯测量模块不依赖全局 window 初始化。 */
export function setDiagnosticSender(sender: (batch: DiagnosticBatch) => Promise<unknown>) { sendBatch = sender; }

/** 最近手势进入同一个有界缓冲；时间、路由和视口用于区分输入惯性与处理积压。 */
export function recordWheel(event: WheelEvent, detail: Record<string, number> = {}) {
  recordCanvasEvent("wheel", { deltaX: event.deltaX, deltaY: event.deltaY, deltaMode: event.deltaMode,
    shiftKey: Number(event.shiftKey), ctrlKey: Number(event.ctrlKey), cancelable: Number(event.cancelable),
    eventTime: event.timeStamp, handledAt: performance.now(), ...detail });
}

/** 仅内部模式启用的有界本地诊断；只收集时序和计数，不保存源码或交互值。 */
export function recordCanvasEvent(kind: string, detail: Record<string, string | number> = {}) {
  if (typeof window === "undefined" || !window.__lessplainBoot?.diagnostics) return;
  if (!installed) {
    installed = true;
    let delivered = 0, sending = false, stopped = false;
    const send = async () => {
      if (sending || stopped || !sendBatch) return;
      sending = true;
      const batch = events.filter(event => Number(event.sequence) > delivered).slice(-128).map(event =>
        Object.fromEntries(Object.entries(event).filter(([key, value]) => key === "kind" || typeof value === "number")) as CanvasEvent);
      try {
        await sendBatch({ visible: !document.hidden, events: batch });
        if (batch.length) delivered = Number(batch.at(-1)!.sequence);
      } catch { /* 故障时由 JVM 的缓存取证，不重试轰炸失联 bridge。 */ }
      finally { sending = false; }
    };
    const timer = window.setInterval(() => { void send(); }, 2000);
    document.addEventListener("visibilitychange", send);
    window.addEventListener("pagehide", () => {
      stopped = true; clearInterval(timer); document.removeEventListener("visibilitychange", send);
    }, { once: true });
    Object.assign(window, { __lessplainDiagnostics: {
      events: () => events.map(event => ({ ...event })),
      clear: () => { events.length = 0; },
      theme: () => {
        const style = getComputedStyle(document.documentElement);
        return Object.fromEntries([...style].filter(key => key.startsWith("--")).map(key => [key, style.getPropertyValue(key).trim()]));
      },
    } });
    if (PerformanceObserver.supportedEntryTypes.includes("longtask")) {
      new PerformanceObserver(list => list.getEntries().forEach(entry =>
        recordCanvasEvent("long-task", { duration: entry.duration, start: entry.startTime }))).observe({ type: "longtask", buffered: true });
    }
  }
  events.push({ time: performance.now(), sequence: ++sequence, kind, ...detail });
  if (events.length > 500) events.shift();
  while (events.length && events[0].time < performance.now() - 120_000) events.shift();
  performance.clearMarks("lessplain:" + kind);
  performance.mark("lessplain:" + kind, { detail });
}
