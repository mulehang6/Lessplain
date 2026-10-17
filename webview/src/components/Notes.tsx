import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { call } from "../runtime/bridge";
import { closeAnnotation, useApp } from "../runtime/store";
import { pendingQuestions, questionsForAgent, scopedQuestions } from "../runtime/questions";
import type { Note } from "../runtime/types";
import { CanvasButton } from "./CanvasButton";
import { ControlIcon } from "./ControlIcon";

export const act = (method: string, params: unknown) => call(method, params).catch(error => useApp.getState().setError(error.message));

export function CopyQuestions({ compact = false }: { compact?: boolean }) {
  const snapshot = useApp(s => s.snapshot), selected = useApp(s => s.selected);
  const notes = selected ? scopedQuestions(snapshot, selected) : [], count = pendingQuestions(notes).length;
  const [copied, setCopied] = useState<{ message: string }>();
  const [showQuestions, setShowQuestions] = useState(false);
  const wrapper = useRef<HTMLDivElement>(null);
  const pendingRefs = pendingQuestions(notes).map(note => note.ref).join(",");
  useEffect(() => setCopied(undefined), [pendingRefs, selected]);
  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(undefined), 3000);
    return () => clearTimeout(timer);
  }, [copied]);
  useEffect(() => {
    if (!showQuestions) return;
    const close = (event: PointerEvent) => { if (!wrapper.current?.contains(event.target as Node)) setShowQuestions(false); };
    document.addEventListener("pointerdown", close);
    return () => document.removeEventListener("pointerdown", close);
  }, [showQuestions]);
  const label = "复制全部问题" + (count > 0 ? " (" + count + ")" : "");
  return <div ref={wrapper} className={"copy-questions" + (compact ? " compact" : "")}
    onContextMenu={event => { event.preventDefault(); event.stopPropagation(); setShowQuestions(true); }}>
    <CanvasButton aria-label={label} title={label + "；右键查看问题"} disabled={!count} onClick={async () => {
    try {
      const state = useApp.getState();
      const questions = state.selected ? scopedQuestions(state.snapshot, state.selected) : [];
      const text = questionsForAgent(questions);
      if (!text) return;
      await call("copy", { text });
      setCopied({ message: `已复制 ${pendingQuestions(questions).length} 条问题，可粘贴到 Agent` });
    } catch (error) {
      setCopied(undefined);
      useApp.getState().setError("复制失败：" + (error instanceof Error ? error.message : String(error)));
    }
  }}>{compact ? <><ControlIcon name="copy" />{count > 0 && <sup>{count}</sup>}</> : label}</CanvasButton>
    {copied && createPortal(<div role="status" className="copy-feedback">{copied.message}</div>, document.body)}
    {showQuestions && <div role="dialog" aria-label="当前理解的问题" className="questions-popover" onKeyDown={event => {
      if (event.key === "Escape") { event.stopPropagation(); setShowQuestions(false); }
    }}><Notes treeScope /><CanvasButton onClick={() => setShowQuestions(false)}>关闭</CanvasButton></div>}
  </div>;
}

/** 页面下方仅回看已添加的注释；全局待答列表收集所有页面和源码问题。 */
export function Notes({ pendingOnly = false, elementId, nodeId, treeScope = false, compact = false }: { pendingOnly?: boolean; elementId?: string; nodeId?: string; treeScope?: boolean; compact?: boolean }) {
  const selected = useApp(s => s.selected), snapshot = useApp(s => s.snapshot), notes = snapshot.notes;
  const scoped = treeScope && selected ? pendingQuestions(scopedQuestions(snapshot, selected))
    : pendingOnly ? pendingQuestions(notes) : notes.filter(note => note.ownerRef === selected && note.status === "pending");
  const visible = elementId || nodeId ? scoped.filter(note => note.origin.elementId === elementId && note.origin.nodeId === nodeId) : scoped;
  if (!visible.length) return pendingOnly ? <p className="muted">没有待答问题</p> : null;
  return <section className="notes" aria-label={pendingOnly ? "全部待答问题" : "当前答案的注释"}>
    <ol>{visible.map((note, index) => <NoteRow key={note.ref} note={note} number={index + 1} compact={compact} />)}</ol>
  </section>;
}
function NoteRow({ note, number, compact }: { note: Note; number: number; compact: boolean }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState<string>();
  const remove = async () => {
    if (busy) return;
    setBusy(true); setError(undefined);
    try { await call("deleteNote", { ref: note.ref }); }
    catch (error) { setError(String(error)); }
    finally { setBusy(false); }
  };
  return <li className="note"><span className="note-number">{number}.</span><div className="note-body">
    <p>{note.text}</p>
    {!compact && note.origin.nodeLabel && <small className="muted">{note.origin.nodeLabel}</small>}
    {error && <p role="alert">{error}</p>}
    <CanvasButton className="note-source-details" onClick={() => {
      closeAnnotation();
      void act("noteDetails", { ref: note.ref });
    }}>查看详情</CanvasButton>
  </div><div className="note-actions">
    <CanvasButton className="note-delete" aria-label="删除注释" title="删除" disabled={busy} onClick={() => void remove()}><ControlIcon name="delete" /></CanvasButton>
  </div></li>;
}
