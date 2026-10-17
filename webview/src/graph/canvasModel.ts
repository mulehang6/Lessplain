import type { Spec } from "@json-render/core";
import { boardEdge, graphProps, type GraphProps } from "../catalog";

export type CanvasGroupDefinition = {
  id: string;
  nodeId: string;
  title: string;
  diagramId?: string;
  companions: string[];
};

export type CanvasNodeDefinition = {
  id: string;
  parentId?: string;
  kind: "group" | "graph" | "companion";
  elementId: string;
  nodeId: string;
  label: string;
  source?: string;
  sample?: GraphProps["nodes"][number]["sample"];
  roles?: GraphProps["nodes"][number]["roles"];
  outcome?: GraphProps["nodes"][number]["outcome"];
};

export type CanvasEdgeDefinition = {
  id: string;
  source: string;
  target: string;
  sourceElementId: string;
  sourceNodeId: string;
  targetElementId: string;
  targetNodeId: string;
  kind: GraphProps["edges"][number]["kind"];
  label?: string;
  summary?: string;
  evidence?: string[];
  inferred?: boolean;
};

export type CanvasModel = {
  boardId: string;
  groups: CanvasGroupDefinition[];
  nodes: CanvasNodeDefinition[];
  edges: CanvasEdgeDefinition[];
  graphProps: Record<string, GraphProps>;
};

/** Build one flat, namespaced graph model from the Board, Cluster, Graph and companion slots. */
export function buildCanvasModel(spec: Spec): CanvasModel {
  const root = spec.elements[spec.root];
  const boardId = root?.type === "Board" ? spec.root : "__canvas_board";
  const groupIds = root?.type === "Board"
    ? root.children ?? []
    : root?.type === "Cluster" ? [spec.root] : [];
  const groups: CanvasGroupDefinition[] = [];
  const nodes: CanvasNodeDefinition[] = [];
  const edges: CanvasEdgeDefinition[] = [];
  const graphPropsByElement: Record<string, GraphProps> = {};
  const nodeByReference = new Set<string>();

  for (const elementId of groupIds) {
    const cluster = spec.elements[elementId];
    if (cluster?.type !== "Cluster") throw new Error("画布成员必须是 Cluster：" + elementId);
    const diagramIds = cluster.slots?.diagram ?? [];
    if (diagramIds.length > 1 || diagramIds.some(id => spec.elements[id]?.type !== "Graph")) {
      throw new Error("Cluster 的 diagram 插槽无效：" + elementId);
    }
    const diagramId = diagramIds.length === 1 && spec.elements[diagramIds[0]]?.type === "Graph"
      ? diagramIds[0]
      : undefined;
    const companions = (cluster.slots?.companion ?? []).filter(id => Boolean(spec.elements[id]) && id !== diagramId);
    const titleValue = cluster.props.title;
    const title = typeof titleValue === "string" && titleValue.trim() ? titleValue : "分组";
    const groupNode = groupNodeId(elementId);
    groups.push({ id: elementId, nodeId: groupNode, title, diagramId, companions });
    nodes.push({
      id: groupNode,
      kind: "group",
      elementId,
      nodeId: elementId,
      label: title,
    });

    if (diagramId) {
      const diagram = spec.elements[diagramId];
      if (diagram.children?.length || Object.values(diagram.slots ?? {}).flat().length) throw new Error("Graph 不能包含普通组件：" + diagramId);
      const parsed = graphProps.safeParse(spec.elements[diagramId].props);
      if (!parsed.success) throw new Error("图无法显示：" + diagramId + "；" + parsed.error.issues.slice(0, 6).map(issue => issue.path.join(".") + " " + issue.message).join("；"));
      if (parsed.success) {
        graphPropsByElement[diagramId] = parsed.data;
        for (const graphNode of parsed.data.nodes) {
          const key = referenceKey(diagramId, graphNode.id);
          if (nodeByReference.has(key)) throw new Error("图节点重复：" + diagramId + "/" + graphNode.id);
          nodeByReference.add(key);
          nodes.push({
            id: graphNodeId(diagramId, graphNode.id),
            parentId: groupNode,
            kind: "graph",
            elementId: diagramId,
            nodeId: graphNode.id,
            label: graphNode.label,
            source: graphNode.source,
            sample: graphNode.sample,
            outcome: graphNode.outcome,
            roles: graphNode.roles,
          });
        }
        for (const edge of parsed.data.edges) {
          const source = graphNodeId(diagramId, edge.source);
          const target = graphNodeId(diagramId, edge.target);
          if (!nodeByReference.has(referenceKey(diagramId, edge.source))
            || !nodeByReference.has(referenceKey(diagramId, edge.target))) throw new Error("图关系端点不存在：" + diagramId + "/" + edge.id);
          edges.push({
            ...edge,
            id: graphEdgeId(diagramId, edge.id),
            source,
            target,
            sourceElementId: diagramId,
            sourceNodeId: edge.source,
            targetElementId: diagramId,
            targetNodeId: edge.target,
          });
        }
      }
    }

    for (const companionId of companions) {
      const element = spec.elements[companionId];
      const label = companionLabel(element?.props) ?? element?.type ?? "组件";
      nodes.push({
        id: companionNodeId(companionId),
        parentId: groupNode,
        kind: "companion",
        elementId: companionId,
        nodeId: companionId,
        label,
      });
    }
  }

  const boardProps = root?.type === "Board" ? root.props : undefined;
  const boardEdges = boardEdge.array().max(300).safeParse(
    boardProps && typeof boardProps === "object" ? (boardProps as { edges?: unknown }).edges ?? [] : [],
  );
  if (!boardEdges.success) throw new Error("跨组关系格式无效：" + boardId);
  if (boardEdges.success) {
    for (const edge of boardEdges.data) {
      if (edge.source.elementId === edge.target.elementId) throw new Error("同组关系须放在 Graph.edges：" + edge.id);
      const source = graphNodeId(edge.source.elementId, edge.source.nodeId);
      const target = graphNodeId(edge.target.elementId, edge.target.nodeId);
      if (!nodeByReference.has(referenceKey(edge.source.elementId, edge.source.nodeId))
        || !nodeByReference.has(referenceKey(edge.target.elementId, edge.target.nodeId))) throw new Error("跨组关系端点不存在：" + edge.id);
      edges.push({
        ...edge,
        id: boardEdgeId(boardId, edge.id),
        source,
        target,
        sourceElementId: edge.source.elementId,
        sourceNodeId: edge.source.nodeId,
        targetElementId: edge.target.elementId,
        targetNodeId: edge.target.nodeId,
      });
    }
  }

  if (new Set(nodes.map(node => node.id)).size !== nodes.length) throw new Error("画布节点或分组重复引用");
  if (new Set(edges.map(edge => edge.id)).size !== edges.length) throw new Error("画布关系 ID 重复");
  const checked = new Set<string>();
  const checkCoverage = (id: string) => {
    if (checked.has(id)) throw new Error("画布组件重复引用：" + id);
    checked.add(id);
    const element = spec.elements[id];
    if (!element) throw new Error("画布组件不存在：" + id);
    if (element.type === "Graph" && !graphPropsByElement[id]) throw new Error("图未进入 diagram 插槽：" + id);
    element.children?.forEach(checkCoverage);
    Object.values(element.slots ?? {}).flat().forEach(checkCoverage);
  };
  checkCoverage(spec.root);
  return { boardId, groups, nodes, edges, graphProps: graphPropsByElement };
}

export function groupNodeId(elementId: string) {
  return "group/" + JSON.stringify(elementId);
}

export function graphNodeId(elementId: string, nodeId: string) {
  return "graph-node/" + JSON.stringify([elementId, nodeId]);
}

export function companionNodeId(elementId: string) {
  return "companion/" + JSON.stringify(elementId);
}

function graphEdgeId(elementId: string, edgeId: string) {
  return "graph-edge/" + JSON.stringify([elementId, edgeId]);
}

function boardEdgeId(boardId: string, edgeId: string) {
  return "board-edge/" + JSON.stringify([boardId, edgeId]);
}

function referenceKey(elementId: string, nodeId: string) {
  return JSON.stringify([elementId, nodeId]);
}

function companionLabel(props: Record<string, unknown> | undefined): string | undefined {
  if (!props) return undefined;
  for (const key of ["title", "label", "text"]) {
    const value = props[key];
    if (typeof value === "string" && value.trim()) return value;
  }
  return undefined;
}
