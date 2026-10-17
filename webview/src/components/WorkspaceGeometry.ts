import type { Spec } from "@json-render/core";

/** 历史普通容器中的图也投影到同一画布；原始记录、元素身份和源码引用不变。 */
export function wrapRootContent(spec: Spec, title: string): Spec {
  const root = spec.elements[spec.root];
  if (!root) throw new Error("页面根组件不存在：" + spec.root);
  if (root.type === "Board" || root.type === "Cluster") return spec;
  const elements = { ...spec.elements };
  const uniqueId = (base: string) => {
    let candidate = base, suffix = 1;
    while (elements[candidate]) candidate = base + "_" + suffix++;
    return candidate;
  };
  const groups: string[] = [];
  const edges: unknown[] = [];
  const visiting = new Set<string>();
  const visited = new Set<string>();
  let graphNumber = 0;
  const addGraph = (id: string) => {
    const clusterId = uniqueId("__workspace_cluster");
    elements[clusterId] = { type: "Cluster", props: { title: graphNumber++ ? title + " · 图 " + graphNumber : title }, slots: { diagram: [id] } };
    groups.push(clusterId);
  };
  const project = (id: string): string | undefined => {
    if (visiting.has(id) || visited.has(id)) throw new Error("页面组件循环或重复引用：" + id);
    const element = elements[id];
    if (!element) throw new Error("页面组件不存在：" + id);
    visited.add(id);
    visiting.add(id);
    if (element.type === "Graph") { addGraph(id); visiting.delete(id); return undefined; }
    if (element.type === "Cluster") { groups.push(id); visiting.delete(id); return undefined; }
    if (element.type === "Board") {
      for (const childId of element.children ?? []) {
        if (elements[childId]?.type !== "Cluster") throw new Error("Board 成员必须是 Cluster：" + childId);
        project(childId);
      }
      for (const raw of (element.props.edges as Record<string, unknown>[] | undefined) ?? []) {
        edges.push({ ...raw, id: id + "/" + raw.id });
      }
      visiting.delete(id);
      return undefined;
    }
    const children = element.children?.flatMap(child => { const kept = project(child); return kept ? [kept] : []; });
    const slots = element.slots && Object.fromEntries(Object.entries(element.slots).map(([slot, ids]) => [slot,
      ids.flatMap(child => { const kept = project(child); return kept ? [kept] : []; }),
    ]));
    visiting.delete(id);
    // 只拆出结构成员；普通组件和事件/状态绑定保留，空布局容器不留下空白节点。
    const hadMembers = (element.children?.length ?? 0) + Object.values(element.slots ?? {}).flat().length > 0;
    const hasMembers = (children?.length ?? 0) + Object.values(slots ?? {}).flat().length > 0;
    if (hadMembers && !hasMembers && ["Stack", "Grid"].includes(element.type)) return undefined;
    elements[id] = { ...element, ...(children ? { children } : {}), ...(slots ? { slots } : {}) };
    return id;
  };
  const companion = project(spec.root);
  if (companion) {
    const clusterId = uniqueId("__workspace_cluster");
    elements[clusterId] = { type: "Cluster", props: { title: groups.length ? "补充内容" : title }, slots: { companion: [companion] } };
    groups.push(clusterId);
  }
  const boardId = uniqueId("__workspace_board");
  elements[boardId] = { type: "Board", props: edges.length ? { edges } : {}, children: groups };
  return { ...spec, root: boardId, elements };
}
