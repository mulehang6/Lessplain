import { useEffect, useRef, useState } from "react";
import { currentView, lessonStore, origin, useApp } from "../runtime/store";
import { call } from "../runtime/bridge";
import { setCanvasCursor } from "../runtime/cursor";
import { openCodeSample } from "../runtime/samples";

export function Demo({ demo, title, height = 300, elementId }: { demo: string; title: string; height?: number; elementId?: string }) {
  const iframe = useRef<HTMLIFrameElement>(null);
  const [size, setSize] = useState(height), [error, setError] = useState<string>();
  const lesson = useApp(state => state.lesson);
  const html = currentView()?.document.demos[demo]?.html;
  useEffect(() => {
    const receive = (event: MessageEvent) => {
      if (event.source !== iframe.current?.contentWindow || event.data?.channel !== "lessplain-demo") return;
      const { method, params } = event.data;
      try {
        if (method === "cursor") setCanvasCursor(params?.cursor);
        else if (method === "resize" && Number.isFinite(params?.height)) setSize(Math.min(1200, Math.max(120, params.height)));
        else if (method === "setState" && typeof params?.path === "string") {
          if (!params.path.startsWith("/") || params.path.split("/").some((s: string) => ["__proto__", "constructor", "prototype"].includes(s))) throw new Error("状态路径无效");
          const raw = JSON.stringify(params.value); if (raw.length > 100000) throw new Error("状态过大");
          lessonStore.set(params.path, params.value);
        } else if (method === "openSource" && typeof params?.source === "string" && currentView()?.sources[params.source]) {
          const view = currentView();
          if (!view?.sources[params.source]) return;
          const state = useApp.getState();
          const lesson = freezeSnapshot(structuredClone(state.lesson));
          state.focus(elementId, undefined, params.source);
          void call("source", { ref: view.ref, source: params.source, elementId, revision: view.revision, state: lesson })
            .catch(e => setError(e.message));
        } else if (method === "openSample" && typeof params?.id === "string") {
          openCodeSample({ id: params.id, startLine: params.startLine, endLine: params.endLine }, elementId);
        } else if (method === "ask" && typeof params?.text === "string" && params.text.trim()) {
          useApp.getState().focus(elementId);
          void call("note", { text: params.text, kind: "question", origin: origin("component") }).catch(e => setError(e.message));
        }
      } catch (e) { setError(String(e)); }
    };
    window.addEventListener("message", receive); return () => window.removeEventListener("message", receive);
  }, [elementId]);
  const send = () => {
    const style = getComputedStyle(document.documentElement);
    const theme = Object.fromEntries([["--demo-background", "--card"], ["--demo-foreground", "--foreground"], ["--demo-hover", "--control-hover"],
      ["--demo-border", "--border"], ["--demo-primary", "--primary"], ["--demo-font", "--ide-font"], ["--demo-font-size", "--ide-font-size"]]
      .map(([target, source]) => [target, style.getPropertyValue(source).trim()]).filter(([, value]) => value));
    theme["--demo-color-scheme"] = style.colorScheme;
    iframe.current?.contentWindow?.postMessage({ channel: "lessplain-demo", method: "state", params: { state: lesson, theme } }, "*");
  };
  useEffect(send, [lesson]);
  useEffect(() => {
    const observer = new MutationObserver(send);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["style", "class"] });
    return () => observer.disconnect();
  }, [lesson]);
  // sandbox 无同源权限；CSP 禁用网络。示例不能调用 IDE bridge 或读取父页面。
  const policy = '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'; style-src \'unsafe-inline\'; img-src data:; font-src data:; connect-src \'none\'; form-action \'none\'; base-uri \'none\';">';
  const cursorBridge = '<script>let last;addEventListener("pointerover",e=>{const cursor=getComputedStyle(e.target).cursor;if(cursor!==last){last=cursor;parent.postMessage({channel:"lessplain-demo",method:"cursor",params:{cursor}},"*")}});addEventListener("pointerout",e=>{if(!e.relatedTarget){last="default";parent.postMessage({channel:"lessplain-demo",method:"cursor",params:{cursor:last}},"*")}});</script>';
  const themeBridge = '<script>addEventListener("message",e=>{if(e.source!==parent||e.data?.channel!=="lessplain-demo"||e.data.method!=="state")return;for(const [k,v]of Object.entries(e.data.params?.theme??{}))if(k.startsWith("--demo-")&&typeof v==="string")document.documentElement.style.setProperty(k,v)})</script>';
  // 隔离文档有自己的 Canvas 默认背景；只设置 iframe 元素背景仍可能被浅色内部页面覆盖。
  const content = policy + '<style>:root{background:var(--demo-background,transparent);color-scheme:var(--demo-color-scheme,light)}body{font:var(--demo-font-size,14px) var(--demo-font,system-ui);color:var(--demo-foreground,#31343b);margin:16px}button,input,select{font:inherit}</style>' + (html ?? "") + themeBridge + cursorBridge;
  return <section className="demo"><div className="component-title">{title}</div>
    {error && <p role="alert">{error}</p>}
    <iframe ref={iframe} title={title} sandbox="allow-scripts" srcDoc={content} style={{ height: size }} onLoad={send}
      onFocus={() => { if (elementId) useApp.getState().focus(elementId); }} />
  </section>;
}

function freezeSnapshot<T>(value: T): T {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    Object.values(value as Record<string, unknown>).forEach(freezeSnapshot);
  }
  return value;
}
