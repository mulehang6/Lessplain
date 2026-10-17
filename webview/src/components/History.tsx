import { useRef, useState } from "react";
import { useApp } from "../runtime/store";
import type { View } from "../runtime/types";
import { HistoryActions, type HistoryTarget } from "./HistoryActions";
import { elementAnchor } from "./popupGeometry";

/** 历史行只负责选择；右键管理不会切换当前答案。 */
export function History() {
  const snapshot = useApp(state => state.snapshot), selected = useApp(state => state.selected);
  const [query, setQuery] = useState(""), [target, setTarget] = useState<HistoryTarget>();
  const opening = useRef(0);
  const rows = snapshot.views.filter(view => !query || ((view.document.title ?? "") + view.document.question).toLowerCase().includes(query.toLowerCase()));
  const open = (view: View, anchor: HistoryTarget["anchor"]) => setTarget({ opening: ++opening.current, ref: view.ref, title: view.document.title ?? view.document.question, anchor });
  const renderTree = (parentRef?: string): React.ReactNode => rows.filter(view => query || view.parentRef === parentRef).map(view => {
    const count = snapshot.notes.filter(note => note.ownerRef === view.ref && note.status === "pending").length;
    return <li key={view.ref}><button data-view-ref={view.ref} className={selected === view.ref ? "history-row active" : "history-row"}
      onClick={() => useApp.getState().select(view.ref)}
      onContextMenu={event => { event.preventDefault(); event.stopPropagation(); open(view, { left: event.clientX, right: event.clientX, top: event.clientY - 6, bottom: event.clientY - 6 }); }}
      onKeyDown={event => {
        if (event.key === "ContextMenu" || (event.shiftKey && event.key === "F10")) {
          event.preventDefault(); event.stopPropagation(); open(view, elementAnchor(event.currentTarget));
        }
      }}>
      <span>{view.document.title ?? view.document.question}</span>{count > 0 && <em>{count}</em>}
      {snapshot.browse[view.ref]?.seenRevision !== view.revision && <small className="unread">新</small>}
    </button>{!query && <ol>{renderTree(view.ref)}</ol>}</li>;
  });
  return <section className="answer-list"><h1>历史记录</h1>
    {snapshot.views.length ? <><input aria-label="搜索答案" placeholder="搜索答案" value={query} onChange={event => setQuery(event.target.value)} />
      <ol>{renderTree()}</ol>{!rows.length && <p className="muted">没有匹配的答案</p>}</>
      : <div className="empty-answers"><p>还没有答案</p><p className="muted">在你的 Agent 中提问，答案会显示在这里。</p></div>}
    {target && <HistoryActions key={target.opening} target={target} onClose={() => setTarget(current => current?.opening === target.opening ? undefined : current)} />}
  </section>;
}
