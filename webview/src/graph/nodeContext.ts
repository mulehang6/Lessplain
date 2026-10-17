export type GraphNodeContext = {
  viewRef?: string;
  elementId: string;
  nodeId: string;
  nodeLabel: string;
  sourceId?: string;
  sourcePath?: string;
};

/** Format a small, source-free context payload that an Agent can use to locate a graph node. */
export function formatGraphNodeContext(context: GraphNodeContext): string {
  return JSON.stringify({
    type: "lessplain-node-context",
    ...(context.viewRef ? { viewRef: context.viewRef } : {}),
    elementId: context.elementId,
    nodeId: context.nodeId,
    nodeLabel: context.nodeLabel,
    ...(context.sourceId ? { sourceId: context.sourceId } : {}),
    ...(context.sourcePath ? { sourcePath: context.sourcePath } : {}),
  });
}
