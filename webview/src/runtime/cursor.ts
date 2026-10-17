import { useEffect } from "react";
import { call } from "./bridge";

export type CanvasCursor = "default" | "pointer" | "text" | "grab" | "grabbing";
const allowed = new Set<CanvasCursor>(["default", "pointer", "text", "grab", "grabbing"]);
let lastCursor: CanvasCursor | undefined;

export function normalizeCursor(value: unknown): CanvasCursor {
  return typeof value === "string" && allowed.has(value as CanvasCursor) ? value as CanvasCursor : "default";
}

/** Publish semantic changes, never every pointer frame; iframe messages use the same allowlist. */
export function setCanvasCursor(value: unknown) {
  const cursor = normalizeCursor(value);
  if (cursor === lastCursor || !window.__lessplainQuery) return;
  lastCursor = cursor;
  void call("cursor", { cursor }).catch(() => { if (lastCursor === cursor) lastCursor = undefined; });
}

export function useCanvasCursor() {
  useEffect(() => {
    let dragging = false;
    const targetCursor = (target: EventTarget | null) => target instanceof Element ? getComputedStyle(target).cursor : "default";
    const over = (event: PointerEvent) => { if (!dragging) setCanvasCursor(targetCursor(event.target)); };
    const down = (event: PointerEvent) => {
      const target = event.target instanceof Element ? event.target : undefined;
      dragging = Boolean(target?.closest(".global-canvas-shell") && !target.closest("button,input,textarea,select,iframe,.nodrag") &&
        (event.button === 2 || (event.button === 0 && target.closest(".react-flow__node.draggable"))));
      setCanvasCursor(dragging ? "grabbing" : targetCursor(event.target));
    };
    const up = (event: PointerEvent) => { dragging = false; setCanvasCursor(targetCursor(document.elementFromPoint(event.clientX, event.clientY))); };
    const out = (event: PointerEvent) => { if (!event.relatedTarget) setCanvasCursor("default"); };
    const reset = () => { dragging = false; setCanvasCursor("default"); };
    window.addEventListener("pointerover", over, true);
    window.addEventListener("pointerdown", down, true);
    window.addEventListener("pointerup", up, true);
    window.addEventListener("pointercancel", reset, true);
    window.addEventListener("pointerout", out, true);
    window.addEventListener("blur", reset);
    window.addEventListener("lessplain-connected", reset);
    return () => {
      window.removeEventListener("pointerover", over, true); window.removeEventListener("pointerdown", down, true);
      window.removeEventListener("pointerup", up, true); window.removeEventListener("pointercancel", reset, true);
      window.removeEventListener("pointerout", out, true); window.removeEventListener("blur", reset);
      window.removeEventListener("lessplain-connected", reset); reset();
    };
  }, []);
}
