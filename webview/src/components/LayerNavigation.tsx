import { useEffect, useRef, useState } from "react";
import { useApp } from "../runtime/store";
import { viewLayers } from "../graph/navigation";
import { CanvasButton } from "./CanvasButton";
import { ControlIcon } from "./ControlIcon";

/** 切换当前理解树的答案；每个浏览器保留自己的宿主和选择。 */
export function LayerNavigation() {
  const views = useApp(s => s.snapshot.views), selected = useApp(s => s.selected);
  const [open, setOpen] = useState(false), button = useRef<HTMLButtonElement>(null);
  const layers = selected ? viewLayers(views, selected) : [];
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (!button.current?.parentElement?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener("pointerdown", outside); return () => document.removeEventListener("pointerdown", outside);
  }, [open]);
  if (layers.length < 2) return null;
  return <div className="graph-layer-navigation"><CanvasButton ref={button} aria-label="理解层级" title="理解层级" aria-expanded={open} onClick={() => setOpen(!open)}><ControlIcon name="layers" /></CanvasButton>
    {open && <div className="graph-popup layer-popup" aria-label="理解层级" onKeyDown={event => {
      if (event.key === "Escape") { event.stopPropagation(); setOpen(false); button.current?.focus(); }
    }}><strong>理解层级</strong><ol>{layers.map(({ view, depth }) => <li key={view.ref}><button
      style={{ paddingLeft: 8 + depth * 12 }} aria-current={selected === view.ref ? "page" : undefined}
      onClick={() => { setOpen(false); useApp.getState().select(view.ref); }}>
      {depth > 0 ? "↳ " : ""}{view.origin.nodeLabel ? view.origin.nodeLabel + " 的追问" : view.document.title ?? view.document.question}
    </button></li>)}</ol></div>}
  </div>;
}
