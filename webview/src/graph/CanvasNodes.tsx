import { memo, useLayoutEffect, useRef, useState, type MouseEvent as ReactMouseEvent, type ReactNode } from "react";
import {
  BaseEdge, EdgeLabelRenderer, Handle, Position, type Edge, type EdgeProps, type Node, type NodeProps,
} from "@xyflow/react";
import type { ElkEdgeSection } from "elkjs/lib/elk-api";
import { CanvasButton } from "../components/CanvasButton";
import { AnnotationIcon } from "../components/AnnotationIcon";
import { ClusterCanvasNode, type ClusterFlowNode } from "../components/Cluster";
import { edgeLabelPoint } from "./edgeRepair";
import { EdgeDetails } from "./EdgeDetails";
import { useNodeActions } from "./NodeActions";

export type Followup = { ref: string; label: string };
export type LessonNodeData = {
  elementId: string;
  nodeId: string;
  label: string;
  source?: string;
  roles?: readonly string[];
  outcome?: "success" | "failure";
  branch: boolean;
  attention?: "candidate" | "preview";
  onPreview?(): void;
  down: boolean;
  followups: Followup[];
  onAnnotate(anchor: HTMLElement): void;
  onCopy(): void;
  onSelectFollowup(ref: string): void;
};
export type CompanionNodeData = { elementId: string; label: string; contentKey: string; content: ReactNode };
export type LessonFlowNode = Node<LessonNodeData, "lesson">;
export type CompanionFlowNode = Node<CompanionNodeData, "companion">;
export type CanvasFlowNode = LessonFlowNode | CompanionFlowNode | ClusterFlowNode;

export type CanvasEdgeData = {
  sections: ElkEdgeSection[];
  summary?: string;
  evidence?: string[];
  kind: string;
  inferred?: boolean;
  preview?: boolean;
  candidate?: boolean;
  onOpenEvidence?(): void;
  automatic: boolean;
  down: boolean;
  sourceElementId: string;
  sourceNodeId: string;
  targetElementId: string;
  targetNodeId: string;
};
export type CanvasFlowEdge = Edge<CanvasEdgeData, "elk">;

const LessonNode = memo(function LessonNode({ id, data, selected }: NodeProps<LessonFlowNode>) {
  const { visible: actionsVisible, show: showActions, hide: hideActions } = useNodeActions(id);
  const hoverArea = useRef<HTMLDivElement>(null);
  const leave = (event: { relatedTarget: EventTarget | null }) => {
    const nextTarget = event.relatedTarget;
    if (nextTarget instanceof globalThis.Node && hoverArea.current?.contains(nextTarget)) {
      showActions();
      return;
    }
    hideActions();
  };
  const annotate = (event: ReactMouseEvent<HTMLElement>) => { event.stopPropagation(); data.onAnnotate(event.currentTarget); };
  const copy = (event: ReactMouseEvent) => { event.stopPropagation(); data.onCopy(); };

  const semantic = data.roles?.includes("end") ? ` end end-${data.outcome ?? "neutral"}` : data.roles?.includes("start") ? " start" : "";
  return <div ref={hoverArea} className={"graph-node lesson-node" + semantic + (selected ? " selected" : "") + (data.attention ? ` ${data.attention}` : "")}
    data-node-id={data.nodeId} data-element-id={data.elementId}
    data-attention={data.attention} aria-label={data.label}
    onPointerEnter={() => { showActions(); data.onPreview?.(); }} onPointerLeave={leave}
    onPointerMove={showActions} onPointerOver={showActions} onFocus={showActions}>
    <Handle type="target" position={data.down ? Position.Top : Position.Left} id="in" />
    <div className="graph-node-title node-title" title={data.label}>{data.branch && <svg className="node-branch-icon" viewBox="0 0 20 20" aria-label="存在分支" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M3 10h5m0 0 5-5h4m-9 5 5 5h4m-3-13 3 3-3 3m0 4 3 3-3 3" /></svg>}{data.label}</div>
    <div className="graph-node-hover-bridge nodrag nopan" aria-hidden="true" />
    <div className={"graph-node-actions" + (actionsVisible ? " is-visible" : "")} aria-label="节点操作">
      <CanvasButton className="graph-node-action node-annotation nodrag nopan" aria-label={`为节点“${data.label}”添加注释`}
        title="注释与追问" onClick={annotate}><AnnotationIcon />{data.followups.length > 0 && <sup>{data.followups.length}</sup>}</CanvasButton>
      <CanvasButton className="graph-node-action source-copy-button nodrag nopan" aria-label="复制节点上下文"
        title="复制节点上下文" onClick={copy}><CopyIcon /></CanvasButton>
    </div>
    <Handle type="source" position={data.down ? Position.Bottom : Position.Right} id="out" />
  </div>;
});

const CompanionNode = memo(function CompanionNode({ data, selected }: NodeProps<CompanionFlowNode>) {
  return <CompanionContent key={data.contentKey} data={data} selected={selected} />;
});

/** 状态切换只增长预留空间；结构内容替换通过 key 重建，弹层不参与流内测量。 */
function CompanionContent({ data, selected }: { data: CompanionNodeData; selected?: boolean }) {
  const element = useRef<HTMLDivElement>(null);
  const [space, setSpace] = useState({ width: 0, height: 0 });
  useLayoutEffect(() => {
    const node = element.current;
    if (!node) return;
    const measure = () => setSpace(previous => {
      const width = Math.max(previous.width, node.offsetWidth), height = Math.max(previous.height, node.offsetHeight);
      return width === previous.width && height === previous.height ? previous : { width, height };
    });
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return <div ref={element} className={"canvas-companion-node" + (selected ? " selected" : "")} data-element-id={data.elementId}
    style={{ minHeight: space.height || undefined,
      minWidth: space.width ? `min(${space.width}px, calc(var(--canvas-content-width, 100vw) * .76))` : undefined }}>
    <Handle type="target" position={Position.Left} id="in" />
    <div className="canvas-companion-content">{data.content}</div>
    <Handle type="source" position={Position.Right} id="out" />
  </div>;
}

export const canvasNodeTypes = {
  cluster: ClusterCanvasNode,
  lesson: LessonNode,
  companion: CompanionNode,
};

export function ElkEdge({ id, data, label, selected, sourceX, sourceY, targetX, targetY, markerEnd }: EdgeProps<CanvasFlowEdge>) {
  if (!data?.sections.length) return null;
  const { x, y } = edgeLabelPoint(data.sections);
  return <EdgeDetails text={data.summary} onOpen={data.onOpenEvidence}>
    <BaseEdge id={id} markerEnd={markerEnd}
      path={pointsPath(data.sections)}
      style={{ stroke: data.preview ? "var(--diagram-purple)" : data.candidate ? "var(--diagram-warning)" : selected ? "var(--diagram-purple)" : "var(--muted-foreground)", strokeWidth: data.preview ? 4 : data.candidate ? 2.8 : selected ? 2.4 : 1.5,
        strokeDasharray: data.inferred ? "5 4" : undefined }} />
    {label && <EdgeLabelRenderer><span className="edge-label nodrag nopan"
      style={{ transform: "translate(-50%,-50%) translate(" + x + "px," + y + "px)" }}
      onClick={() => data.onOpenEvidence?.()}>
      {String(label)}
    </span></EdgeLabelRenderer>}
  </EdgeDetails>;
}

export const canvasEdgeTypes = { elk: ElkEdge };

function pointsPath(sections: ElkEdgeSection[]) {
  if (!sections.length) return "";
  return sections.map(section => {
    const points = [section.startPoint, ...(section.bendPoints ?? []), section.endPoint].map(point => ({ ...point }));
    return points.map((point, index) => (index ? "L " : "M ") + point.x + " " + point.y).join(" ");
  }).join(" ");
}

function CopyIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.6"><rect x="8" y="8" width="12" height="12" rx="2" /><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3" /></svg>;
}
