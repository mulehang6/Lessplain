import { useEffect, useRef, useState } from "react";
import { call } from "../runtime/bridge";
import { useApp } from "../runtime/store";
import { AnchoredPopup } from "./AnchoredPopup";
import { CanvasButton } from "./CanvasButton";
import type { PopupAnchor } from "./popupGeometry";

export type HistoryTarget = { opening: number; ref: string; title: string; anchor: PopupAnchor };

/** 操作目标固定到右键行；重命名与删除分别提交，不连带修改归属。 */
export function HistoryActions({ target, onClose }: { target: HistoryTarget; onClose(): void }) {
  const [mode, setMode] = useState<"menu" | "rename" | "delete">("menu");
  const [title, setTitle] = useState(target.title), [saving, setSaving] = useState(false), [error, setError] = useState<string>();
  const dialog = useRef<HTMLDialogElement>(null);
  const snapshot = useApp(state => state.snapshot);
  useEffect(() => { if (mode === "delete" && dialog.current && !dialog.current.open) dialog.current.showModal(); }, [mode]);
  const execute = async (method: "rename" | "delete") => {
    if (saving) return;
    setSaving(true); setError(undefined);
    try {
      await call(method, { ref: target.ref, ...(method === "rename" ? { title: title.trim() } : {}) });
      onClose();
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setSaving(false); }
  };
  if (mode === "menu") return <AnchoredPopup anchor={target.anchor} label="历史记录菜单" role="menu" className="history-menu" onDismiss={onClose}
    onKeyDown={event => {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        const items = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')];
        const index = items.indexOf(document.activeElement as HTMLButtonElement);
        items[(index + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length]?.focus();
      }
    }}>
    <CanvasButton role="menuitem" onClick={() => setMode("rename")}>重命名</CanvasButton>
    <CanvasButton role="menuitem" onClick={() => setMode("delete")}>删除</CanvasButton>
  </AnchoredPopup>;
  if (mode === "rename") return <AnchoredPopup key="rename" anchor={target.anchor} label="重命名历史记录" className="history-rename" onDismiss={onClose}>
    <form onSubmit={event => { event.preventDefault(); if (title.trim()) void execute("rename"); }}>
      <label>名称<input aria-label="历史记录名称" value={title} onFocus={event => event.currentTarget.select()} onChange={event => setTitle(event.target.value)} /></label>
      {error && <p role="alert">{error}</p>}
      <div className="row-actions"><CanvasButton disabled={saving || !title.trim()}>保存</CanvasButton><CanvasButton type="button" onClick={onClose}>取消</CanvasButton></div>
    </form>
  </AnchoredPopup>;
  const descendants = new Set([target.ref]);
  let changed = true;
  while (changed) {
    changed = false;
    snapshot.views.forEach(view => {
      if (view.parentRef && descendants.has(view.parentRef) && !descendants.has(view.ref)) { descendants.add(view.ref); changed = true; }
    });
  }
  return <dialog ref={dialog} className="modal delete-history" aria-label="删除历史记录" onCancel={onClose}>
    <h2>删除历史记录</h2><p>删除“{target.title}”及其全部追问、注释和浏览状态？</p>
    {descendants.size > 1 && <p className="muted">包含 {descendants.size - 1} 条追问。删除后无法恢复。</p>}
    {error && <p role="alert">{error}</p>}
    <div className="row-actions"><CanvasButton disabled={saving} onClick={() => void execute("delete")}>确认删除</CanvasButton><CanvasButton onClick={onClose}>取消</CanvasButton></div>
  </dialog>;
}
