import type { Spec } from "@json-render/core";

export type WorkspaceGroup = { id: string; title: string; graphId?: string };
export type WorkspaceBrowseState = { activeClusterId?: string };
export type WorkspaceBrowsePatch = Partial<WorkspaceBrowseState>;

/** The canvas and group navigation are saved once per view and host. */
export function workspaceBrowseKey(host: string) {
  return `workspace:${host}`;
}

export function canvasBrowseKey(host: string) {
  return `canvas:${host}`;
}

/** List the visible clusters and their optional Graph controllers in render order. */
export function workspaceGroups(spec: Spec): WorkspaceGroup[] {
  const root = spec.elements[spec.root];
  const clusterIds = root?.type === "Board"
    ? root.children ?? []
    : root?.type === "Cluster" ? [spec.root] : [];
  return clusterIds.flatMap(id => {
    const cluster = spec.elements[id];
    if (cluster?.type !== "Cluster") return [];
    const diagramIds = cluster.slots?.diagram ?? [];
    const graphId = diagramIds.length === 1 && spec.elements[diagramIds[0]]?.type === "Graph"
      ? diagramIds[0]
      : undefined;
    const title = cluster.props.title;
    return [{ id, title: typeof title === "string" && title.trim() ? title : "分组", graphId }];
  });
}

/** Return graph IDs reachable through the workspace layout, preserving display order. */
export function workspaceGraphIds(spec: Spec, groups = workspaceGroups(spec)): string[] {
  const root = spec.elements[spec.root];
  if (root?.type === "Graph") return [spec.root];
  return groups.flatMap(group => group.graphId ? [group.graphId] : []);
}

/** Restore only reachable group/controller IDs, falling back to the first usable group. */
export function restoreWorkspaceBrowseState(
  value: unknown,
  groups: readonly WorkspaceGroup[],
): WorkspaceBrowseState {
  const saved = isRecord(value) ? value : {};
  const savedGroup = groups.find(group => group.id === saved.activeClusterId);
  return { activeClusterId: savedGroup?.id ?? groups[0]?.id };
}

/** Merge a local workspace update without discarding the other view state fields. */
export function mergeWorkspaceBrowseState(value: unknown, patch: WorkspaceBrowsePatch): WorkspaceBrowseState {
  const saved = isRecord(value) ? value : {};
  const activeClusterId = Object.prototype.hasOwnProperty.call(patch, "activeClusterId")
    ? patch.activeClusterId
    : (typeof saved.activeClusterId === "string" ? saved.activeClusterId : undefined);
  return { activeClusterId };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
