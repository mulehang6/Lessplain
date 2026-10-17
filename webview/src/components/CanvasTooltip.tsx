import { useLayoutEffect, useRef, useState, type RefObject } from "react";
import { createPortal } from "react-dom";

/** Canvas tooltips use DOM content instead of the embedded browser's native title popup. */
export function CanvasTooltip({ id, anchor, text, open }: {
  id: string; anchor: RefObject<Element | null>; text?: string; open: boolean;
}) {
  const [position, setPosition] = useState({ x: 0, y: 0 });
  const popup = useRef<HTMLDivElement | null>(null);
  useLayoutEffect(() => {
    if (!open || !anchor.current) return;
    const bounds = anchor.current.getBoundingClientRect();
    const size = popup.current?.getBoundingClientRect() ?? { width: 0, height: 32 };
    const side = anchor.current.closest(".graph-side-controls") && !anchor.current.closest(".graph-popup");
    const x = side ? bounds.left - size.width - 8 : bounds.left + (bounds.width - size.width) / 2;
    const y = side ? bounds.top + (bounds.height - size.height) / 2
      : bounds.top >= size.height + 16 ? bounds.top - size.height - 8 : bounds.bottom + 8;
    setPosition({ x: Math.max(8, Math.min(innerWidth - size.width - 8, x)), y: Math.max(8, Math.min(innerHeight - size.height - 8, y)) });
  }, [open, anchor, text]);
  if (!open || !text) return null;
  return createPortal(<div ref={popup} id={id} role="tooltip" className="canvas-tooltip"
    style={{ left: position.x, top: position.y }}>{text}</div>, document.body);
}
