import type { ReactNode } from "react";
import { useShallow } from "zustand/react/shallow";
import { beginAnnotation, useApp } from "../runtime/store";
import { CanvasButton } from "./CanvasButton";
import { AnnotationIcon } from "./AnnotationIcon";
import { elementAnchor } from "./popupGeometry";

/** 元数据订阅只更新当前组件标记，不重建 registry 或改变流内测量。 */
export function ComponentFrame({ id, type, source, children }: { id: string; type: string; source?: string; children: ReactNode }) {
  const [pending, answered] = useApp(useShallow(state => {
    const owns = (origin: { viewRef?: string; elementId?: string; nodeId?: string; source?: unknown; sample?: unknown }) =>
      origin.viewRef === state.selected && origin.elementId === id && !origin.nodeId && !origin.source && !origin.sample;
    return [state.snapshot.notes.filter(note => note.status === "pending" && owns(note.origin)).length,
      state.snapshot.views.filter(view => owns(view.origin)).length];
  }));
  const annotated = pending + answered > 0;
  const annotatable = !["Stack", "Grid", "Separator"].includes(type);
  return <div className={"lesson-element" + (annotated ? " has-annotations" : "")} data-element-id={id} data-component-type={type}
    onClick={event => { event.stopPropagation(); useApp.getState().focus(id, undefined, source); }}>
    {annotatable && <CanvasButton className="annotation-trigger nodrag nopan" aria-label="为组件添加注释"
      title={annotated ? `${pending} 条待答，${answered} 条已回复` : "为组件添加注释"}
      data-pending-count={pending} data-answer-count={answered}
      onClick={event => { event.stopPropagation(); useApp.getState().focus(id); beginAnnotation(undefined, elementAnchor(event.currentTarget)); }}>
      <AnnotationIcon />{pending > 0 ? <sup>{pending}</sup> : answered > 0 ? <span className="annotation-history-dot" aria-hidden="true" /> : null}
    </CanvasButton>}
    {children}
  </div>;
}
