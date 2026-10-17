import type { ReactNode } from "react";

export type ControlIconName = "plus" | "minus" | "fit" | "layout" | "previous" | "next" | "more" | "layers" | "groups" | "copy" | "edit" | "delete" | "close" | "send";
const paths: Record<ControlIconName, ReactNode> = {
  plus: <path d="M12 5v14M5 12h14" />,
  minus: <path d="M5 12h14" />,
  fit: <path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5M8 8l8 8m0-8-8 8" />,
  layout: <><rect x="3" y="3" width="7" height="5" rx="1" /><rect x="14" y="16" width="7" height="5" rx="1" /><path d="M6.5 8v10.5H14M10 5.5h7.5V16" /></>,
  previous: <path d="m11 5-7 7 7 7m-7-7h16" />,
  next: <path d="m13 5 7 7-7 7M4 12h16" />,
  more: <><circle cx="5" cy="12" r="1" fill="currentColor" /><circle cx="12" cy="12" r="1" fill="currentColor" /><circle cx="19" cy="12" r="1" fill="currentColor" /></>,
  layers: <path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5" />,
  groups: <><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M9 9v12M14 13h4m-4 4h4" /></>,
  copy: <><rect x="8" y="8" width="12" height="12" rx="2" /><path d="M5 16H4V4h12v1" /></>,
  edit: <path d="m15 4 5 5M4 20l5-1L20 8a2 2 0 0 0-5-5L4 14v6Z" />,
  delete: <path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7" />,
  close: <path d="m6 6 12 12M6 18 18 6" />,
  send: <path d="m3 4 18 8-18 8 4-8-4-8Zm4 8h14" />,
};

/** 固定工具条和弹层动作使用同一线宽；节点 hover 图标保持原实现。 */
export function ControlIcon({ name }: { name: ControlIconName }) {
  return <svg className="control-icon" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}
