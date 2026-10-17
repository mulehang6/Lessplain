type CanvasEvent = { time: number; kind: string; [key: string]: string | number };
const events: CanvasEvent[] = [];
let installed = false;

/** 仅内部模式启用的有界本地诊断；只收集时序和计数，不保存源码或交互值。 */
export function recordCanvasEvent(kind: string, detail: Record<string, string | number> = {}) {
  if (typeof window === "undefined" || !window.__lessplainBoot?.diagnostics) return;
  if (!installed) {
    installed = true;
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
  events.push({ time: performance.now(), kind, ...detail });
  if (events.length > 500) events.shift();
  performance.clearMarks("lessplain:" + kind);
  performance.mark("lessplain:" + kind, { detail });
}
