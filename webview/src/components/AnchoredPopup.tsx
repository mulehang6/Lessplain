import { useLayoutEffect, useRef, useState, type ReactNode, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import { popupPosition, type PopupAnchor } from "./popupGeometry";
import { dismissTooltips } from "./tooltipInput";

/** 非模态弹层锚定触发位置；外部点击仍可操作画布。 */
export function AnchoredPopup({ anchor, label, role = "dialog", children, onDismiss, className = "", onKeyDown }: {
  anchor?: PopupAnchor; label: string; role?: "dialog" | "menu"; children: ReactNode;
  onDismiss(): void; className?: string; onKeyDown?(event: KeyboardEvent<HTMLDivElement>): void;
}) {
  const popup = useRef<HTMLDivElement>(null);
  const previousFocus = useRef(document.activeElement instanceof HTMLElement ? document.activeElement : undefined);
  const restoreFocus = useRef(true);
  const dismiss = useRef(onDismiss); dismiss.current = onDismiss;
  const [position, setPosition] = useState<{ left: number; top: number }>();
  useLayoutEffect(() => {
    const element = popup.current;
    if (!element) return;
    const update = () => {
      const bounds = element.getBoundingClientRect();
      const next = popupPosition(anchor, bounds, { width: innerWidth, height: innerHeight });
      setPosition(old => old?.left === next.left && old.top === next.top ? old : next);
    };
    update();
    const observer = new ResizeObserver(update); observer.observe(element);
    window.addEventListener("resize", update);
    return () => { observer.disconnect(); window.removeEventListener("resize", update); };
  }, [anchor]);
  useLayoutEffect(() => {
    const element = popup.current;
    const outside = (event: PointerEvent | WheelEvent) => {
      if (element?.contains(event.target as Node)) return;
      restoreFocus.current = false; dismiss.current();
    };
    const move = () => { restoreFocus.current = false; dismiss.current(); };
    const escape = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented && !event.isComposing) {
        event.preventDefault(); event.stopPropagation(); dismiss.current();
      }
    };
    document.addEventListener("pointerdown", outside, true);
    document.addEventListener("wheel", outside, { passive: true, capture: true });
    window.addEventListener("lessplain:canvas-move", move);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("pointerdown", outside, true); document.removeEventListener("wheel", outside, true);
      window.removeEventListener("lessplain:canvas-move", move);
      document.removeEventListener("keydown", escape);
      if (restoreFocus.current && previousFocus.current?.isConnected) previousFocus.current.focus();
    };
  }, []);
  useLayoutEffect(() => {
    if (position) { dismissTooltips(); popup.current?.querySelector<HTMLElement>('input:not([disabled]),textarea:not([disabled]),button:not([disabled])')?.focus(); }
  }, [Boolean(position)]);
  return createPortal(<div ref={popup} role={role} aria-label={label} className={"anchored-popup " + className}
    style={{ ...position, visibility: position ? "visible" : "hidden" }}
    onContextMenu={event => { event.preventDefault(); event.stopPropagation(); }}
    onKeyDown={event => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); dismiss.current(); }
      else onKeyDown?.(event);
    }}>{children}</div>, document.body);
}
