import { useEffect, useRef, useState } from "react";
import { CanvasButton } from "./CanvasButton";
import { useWorkspace } from "./WorkspaceContext";
import { ControlIcon } from "./ControlIcon";

/** 切换并定位 Cluster，不创建或切换独立 viewport。 */
export function WorkspaceGroupNavigation() {
  const workspace = useWorkspace();
  const groups = workspace?.groups ?? [];
  const wrapper = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const selectedGroupId = workspace?.activeClusterId ?? groups[0]?.id;

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!wrapper.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", outside);
    return () => document.removeEventListener("pointerdown", outside);
  }, [open]);

  useEffect(() => setOpen(false), [workspace?.activeClusterId]);
  if (groups.length < 2) return null;

  return <div ref={wrapper} className="workspace-group-navigation">
    <CanvasButton aria-label="分组导航" title="分组导航" aria-expanded={open} onClick={() => setOpen(value => !value)}><ControlIcon name="groups" /></CanvasButton>
    {open && <div className="workspace-group-popup graph-popup" role="menu" aria-label="分组导航" onKeyDown={event => {
      if (event.key === "Escape") { event.stopPropagation(); setOpen(false); }
    }}>
      <strong>分组导航</strong>
      <ol>{groups.map(group => <li key={group.id}><CanvasButton role="menuitem" aria-pressed={selectedGroupId === group.id}
        onClick={() => { workspace?.activateCluster(group.id); setOpen(false); }}>{group.title}</CanvasButton></li>)}</ol>
    </div>}
  </div>;
}
