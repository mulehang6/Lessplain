import { forwardRef, useEffect, useId, useRef, useState, type ButtonHTMLAttributes } from "react";
import { CanvasTooltip } from "./CanvasTooltip";
import { isKeyboardFocus } from "./tooltipInput";

/** A canvas action keeps explicit feedback and an accessible tooltip in JCEF. */
export const CanvasButton = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement>>(function CanvasButton({
  title, className, onPointerEnter, onPointerLeave, onPointerDown, onPointerUp, onFocus, onBlur, onClick,
  ...props
}, forwardedRef) {
  const anchor = useRef<HTMLButtonElement | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const id = useId();
  const [hovered, setHovered] = useState(false), [pressed, setPressed] = useState(false), [open, setOpen] = useState(false);
  const close = () => { clearTimeout(timer.current); setOpen(false); };
  useEffect(() => {
    const hide = () => { clearTimeout(timer.current); setOpen(false); };
    window.addEventListener("lessplain:popup-open", hide);
    window.addEventListener("lessplain:canvas-move", hide);
    return () => { clearTimeout(timer.current); window.removeEventListener("lessplain:popup-open", hide); window.removeEventListener("lessplain:canvas-move", hide); };
  }, []);
  useEffect(() => {
    if (!open) return;
    const hide = () => setOpen(false);
    const key = (event: KeyboardEvent) => { if (event.key === "Escape") hide(); };
    window.addEventListener("wheel", hide, { passive: true });
    window.addEventListener("pointerdown", hide);
    window.addEventListener("keydown", key);
    return () => { window.removeEventListener("wheel", hide); window.removeEventListener("pointerdown", hide); window.removeEventListener("keydown", key); };
  }, [open]);
  const text = title ?? props["aria-label"];
  return <><button {...props} ref={node => {
    anchor.current = node;
    if (typeof forwardedRef === "function") forwardedRef(node); else if (forwardedRef) forwardedRef.current = node;
  }} className={"canvas-button " + (className ?? "")} data-hovered={hovered} data-pressed={pressed}
    aria-describedby={[props["aria-describedby"], open && text ? id : undefined].filter(Boolean).join(" ") || undefined}
    onPointerEnter={event => { setHovered(true); clearTimeout(timer.current); if (!props.disabled) timer.current = setTimeout(() => setOpen(true), 500); onPointerEnter?.(event); }}
    onPointerLeave={event => { setHovered(false); setPressed(false); close(); onPointerLeave?.(event); }}
    onPointerDown={event => { setPressed(true); close(); onPointerDown?.(event); }}
    onPointerUp={event => { setPressed(false); onPointerUp?.(event); }}
    onFocus={event => { clearTimeout(timer.current); setOpen(isKeyboardFocus()); onFocus?.(event); }}
    onBlur={event => { close(); setPressed(false); onBlur?.(event); }}
    onClick={event => { close(); onClick?.(event); }} />
    <CanvasTooltip id={id} anchor={anchor} text={text} open={open} />
  </>;
});
