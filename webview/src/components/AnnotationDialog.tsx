import { useEffect, useRef, useState } from "react";
import { call } from "../runtime/bridge";
import { closeAnnotation, updateAnnotation, useApp } from "../runtime/store";
import { AnchoredPopup } from "./AnchoredPopup";
import { CanvasButton } from "./CanvasButton";
import { ControlIcon } from "./ControlIcon";
import { Notes } from "./Notes";

/** 列表和新问题共用一个弹层；新草稿与每条已保存问题的编辑状态相互独立。 */
export function AnnotationDialog() {
  const key = useApp(s => s.annotation), draft = useApp(s => key ? s.drafts[key] : undefined);
  const anchor = useApp(s => s.annotationAnchor);
  const input = useRef<HTMLTextAreaElement>(null);
  const [saving, setSaving] = useState(false), [error, setError] = useState<string>();
  useEffect(() => {
    setError(undefined);
    if (key) input.current?.focus();
  }, [key]);
  if (!draft || !key) return null;
  return <AnchoredPopup key={key} anchor={anchor} className="annotation-dialog" label="注释" onDismiss={() => closeAnnotation()}>
    <Notes compact elementId={draft.origin.elementId} nodeId={draft.origin.nodeId} />
    <form className="annotation-composer" onSubmit={async event => {
      event.preventDefault(); if (saving || !draft.text.trim()) return;
      setSaving(true); setError(undefined);
      try {
        await call("note", { text: draft.text, kind: "question", origin: draft.origin });
        closeAnnotation(key);
      } catch (error) { setError(String(error)); }
      finally {
        setSaving(false);
        requestAnimationFrame(() => { if (useApp.getState().annotation === key) input.current?.focus(); });
      }
    }}>
      <textarea ref={input} aria-label="问题内容" placeholder="写下这里的疑问" disabled={saving} value={draft.text} onChange={e => updateAnnotation({ text: e.target.value })} />
      <div className="annotation-compose-actions"><CanvasButton type="button" disabled={saving} onClick={() => closeAnnotation(key)}>取消</CanvasButton>
        <CanvasButton className="annotation-send" aria-label="发送注释" title="发送" disabled={saving || !draft.text.trim()}><ControlIcon name="send" /></CanvasButton></div>
      {error && <p role="alert">{error}</p>}
    </form>
  </AnchoredPopup>;
}
