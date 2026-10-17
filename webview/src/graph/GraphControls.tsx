import { useEffect, useRef, useState, type ReactNode } from "react";
import { CopyQuestions } from "../components/Notes";
import { CanvasButton } from "../components/CanvasButton";
import { LayerNavigation } from "../components/LayerNavigation";
import type { GraphController } from "../components/WorkspaceContext";
import type { GraphOptions } from "../runtime/types";
import { dismissTooltips } from "../components/tooltipInput";
import { ControlIcon } from "../components/ControlIcon";

/** GraphController 让独立图与混合 Board 共用同一组图表操作。 */
export function GraphControls({ controller, groupNavigation }: { controller: GraphController; groupNavigation?: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [, refreshOptions] = useState(0);
  const settingsButton = useRef<HTMLButtonElement>(null), settings = useRef<HTMLDivElement>(null);
  useEffect(() => { if (open) { dismissTooltips(); settings.current?.querySelector<HTMLButtonElement>("button")?.focus(); } }, [open]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!settings.current?.contains(event.target as Node) && !settingsButton.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", outside);
    return () => document.removeEventListener("pointerdown", outside);
  }, [open]);
  const options = controller.getOptions();
  const change = (value: Partial<GraphOptions>) => {
    controller.setOptions({ ...controller.getOptions(), ...value });
    refreshOptions(revision => revision + 1);
  };
  return <>
    <div className="graph-viewport-controls" aria-label="图表视口">
      <CanvasButton aria-label="放大" title="放大" onClick={controller.zoomIn}><ControlIcon name="plus" /></CanvasButton>
      <CanvasButton aria-label="缩小" title="缩小" onClick={controller.zoomOut}><ControlIcon name="minus" /></CanvasButton>
      <CanvasButton aria-label="显示全图" title="显示全图" onClick={controller.fit}><ControlIcon name="fit" /></CanvasButton>
      <CanvasButton aria-label="一键自动排版" title="一键自动排版" onClick={controller.arrange}><ControlIcon name="layout" /></CanvasButton>
    </div>
    <div className="graph-route-controls" aria-label="图表步骤">
      <CanvasButton aria-label="上一步" title="上一步" disabled={!controller.canPrevious} onClick={controller.stepPrevious}><ControlIcon name="previous" /></CanvasButton>
      <CanvasButton aria-label="下一步" title="下一步" disabled={!controller.canNext} onClick={controller.stepNext}><ControlIcon name="next" /></CanvasButton>
    </div>
    <div className="graph-side-controls" aria-label="图表操作"><CopyQuestions compact /><LayerNavigation />
      {groupNavigation && <div className="graph-workspace-group-navigation">{groupNavigation}</div>}
      <CanvasButton ref={settingsButton} aria-label="画布行为" title="画布行为" aria-expanded={open} onClick={() => setOpen(!open)}><ControlIcon name="more" /></CanvasButton>
      {open && <div ref={settings} className="graph-popup behavior-popup" aria-label="画布行为" onKeyDown={event => {
        if (event.key === "Escape") { event.stopPropagation(); setOpen(false); settingsButton.current?.focus(); }
      }}>
        <strong>画布行为</strong>
        <div className="direction-choices" role="group" aria-label="布局方向">
          {([ ["AUTO", "自动"], ["RIGHT", "横向"], ["DOWN", "纵向"] ] as const).map(([direction, label]) =>
            <CanvasButton key={direction} aria-pressed={options.direction === direction} onClick={() => change({ direction })}>{label}</CanvasButton>)}
        </div>
        <label><input type="checkbox" checked={options.jumpToSource} onChange={event => change({ jumpToSource: event.target.checked })} />自动跳转源码</label>
        <label><input type="checkbox" checked={options.flow} onChange={event => change({ flow: event.target.checked })} />路径流动</label>
        <label><input type="checkbox" checked={options.follow} onChange={event => change({ follow: event.target.checked })} />自动跟随节点</label>
        <CanvasButton onClick={controller.resetZoom}>恢复 100%</CanvasButton>
      </div>}
    </div>
  </>;
}
