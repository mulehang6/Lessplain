import type { Spec } from "@json-render/core";

/** 仅实际组件子树替换时重置预留空间；共享交互状态和注释不属于结构内容。 */
export function componentContentKey(spec: Spec, root: string): string {
  const visited = new Set<string>();
  const elements: unknown[] = [];
  const visit = (id: string) => {
    if (visited.has(id)) return;
    visited.add(id);
    const element = spec.elements[id];
    if (!element) return;
    elements.push([id, element]);
    element.children?.forEach(visit);
    Object.values(element.slots ?? {}).forEach(children => children.forEach(visit));
  };
  visit(root);
  return JSON.stringify(elements);
}
