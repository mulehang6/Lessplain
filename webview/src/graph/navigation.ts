import type { View } from "../runtime/types";

export type NavigationNode = { id: string; roles?: readonly string[] };
export type NavigationEdge = { id: string; source: string; target: string; kind?: string };
export type NavigationTopology<EdgeType extends NavigationEdge = NavigationEdge> = {
  nodes: readonly NavigationNode[];
  edges: readonly EdgeType[];
};

/** 首个 start、无入边节点、首个节点；自环不参与步骤。 */
export function startingNode(props: NavigationTopology): string {
  return (props.nodes.find(node => node.roles?.includes("start"))
    ?? props.nodes.find(node => !props.edges.some(edge => edge.target === node.id && edge.source !== edge.target))
    ?? props.nodes[0])?.id ?? "";
}
export function stepChoices<EdgeType extends NavigationEdge>(props: NavigationTopology<EdgeType>, current: string, direction: "previous" | "next"): EdgeType[] {
  return props.edges.filter(edge => navigable(edge) && edge.source !== edge.target && (direction === "next" ? edge.source === current : edge.target === current));
}
function navigable(edge: NavigationEdge) { return edge.kind === undefined || ["control_flow", "call", "data_flow"].includes(edge.kind); }
/** 多条关系指向同一个后继不构成分支，结构/依赖关系也不参与步骤。 */
export function branchNodes(props: NavigationTopology): Set<string> {
  return new Set(props.nodes.filter(node => new Set(stepChoices(props, node.id, "next").map(edge => edge.target)).size > 1).map(node => node.id));
}
export function cyclePreview(edges: readonly NavigationEdge[], current: string | undefined, delta: number): string | undefined {
  if (!edges.length) return undefined;
  const index = edges.findIndex(edge => edge.id === current);
  return edges[(index + delta + edges.length) % edges.length]?.id;
}
export function upstreamEdges<EdgeType extends NavigationEdge>(props: NavigationTopology<EdgeType>, current: string): Set<string> {
  const visited = new Set<string>(), edges = new Set<string>(), queue = [current];
  while (queue.length) {
    const node = queue.pop()!; if (visited.has(node)) continue; visited.add(node);
    stepChoices(props, node, "previous").forEach(edge => { edges.add(edge.id); queue.push(edge.source); });
  }
  return edges;
}

/** 层级是根答案及其追问树，与单张图内的节点/分组不同。 */
export function viewLayers(views: View[], selected: string): { view: View; depth: number }[] {
  const byRef = new Map(views.map(view => [view.ref, view])), seen = new Set<string>();
  let root = selected;
  while (byRef.get(root)?.parentRef && !seen.has(root)) { seen.add(root); root = byRef.get(root)!.parentRef!; }
  const result: { view: View; depth: number }[] = [];
  const visit = (ref: string, depth: number) => {
    const view = byRef.get(ref); if (!view || result.some(item => item.view.ref === ref)) return;
    result.push({ view, depth });
    views.filter(child => child.parentRef === ref).forEach(child => visit(child.ref, depth + 1));
  };
  visit(root, 0); return result;
}
