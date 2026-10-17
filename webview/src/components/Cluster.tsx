import { memo, type ReactNode } from "react";
import type { Node, NodeProps } from "@xyflow/react";

export type ClusterNodeData = { title: string; clusterId: string; elementId: string };
export type ClusterFlowNode = Node<ClusterNodeData, "cluster">;

/** A measured ELK compound parent that keeps the group title inside its boundary. */
export const ClusterCanvasNode = memo(function ClusterCanvasNode({ data, id }: NodeProps<ClusterFlowNode>) {
  return <div className="canvas-cluster-node" data-cluster-id={data.clusterId} data-element-id={data.elementId} data-flow-id={id}>
    <header className="canvas-cluster-title" role="heading" aria-level={2} data-cluster-pan-handle>{data.title}</header>
  </div>;
});

/** Plain fallback renderer for nested JSON components; the main page uses ClusterCanvasNode. */
export function Cluster({ props, slots }: {
  props: { title: string; __elementId?: string };
  slots?: Record<string, ReactNode>;
}) {
  return <section className="board-cluster" data-cluster-id={props.__elementId}>
    <header className="cluster-title">{props.title}</header>
    <div className="cluster-body">{slots?.diagram}{slots?.companion}</div>
  </section>;
}
