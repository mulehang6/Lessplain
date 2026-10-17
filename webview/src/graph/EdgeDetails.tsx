import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { CanvasTooltip } from "../components/CanvasTooltip";
import { isKeyboardFocus } from "../components/tooltipInput";

/** 连线说明按需呈现，不把 summary 或技术关系名变成常驻文字胶囊。 */
export function EdgeDetails({ text, children, onOpen }: { text?: string; children: ReactNode; onOpen?(): void }) {
  const anchor = useRef<SVGGElement>(null), timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const [open, setOpen] = useState(false), id = useId();
  const dismiss = () => { clearTimeout(timer.current); setOpen(false); };
  useEffect(() => {
    const hide = () => { clearTimeout(timer.current); setOpen(false); };
    for (const name of ["lessplain:popup-open", "lessplain:canvas-move", "wheel", "pointerdown"]) window.addEventListener(name, hide);
    return () => {
      clearTimeout(timer.current);
      for (const name of ["lessplain:popup-open", "lessplain:canvas-move", "wheel", "pointerdown"]) window.removeEventListener(name, hide);
    };
  }, []);
  return <g ref={anchor} tabIndex={text || onOpen ? 0 : undefined} aria-label={text ?? "关系来源"} aria-describedby={open ? id : undefined}
    onPointerEnter={() => { clearTimeout(timer.current); if (text) timer.current = setTimeout(() => setOpen(true), 500); }}
    onPointerLeave={dismiss} onPointerDown={dismiss} onBlur={dismiss}
    onFocus={() => { if (isKeyboardFocus()) setOpen(true); }} onKeyDown={event => {
      if (event.key === "Escape") { dismiss(); event.stopPropagation(); }
      if (event.key === "Enter" && onOpen) { event.preventDefault(); event.stopPropagation(); dismiss(); onOpen(); }
    }}>
    {children}<CanvasTooltip id={id} anchor={anchor} text={text} open={open} />
  </g>;
}
