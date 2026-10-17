import type { View } from "./types";

/** 宿主每次发布都会反序列化所有视图。相同身份和版本复用引用，备注变动不传播成图结构更新。 */
export function shareViews(previous: View[], incoming: View[]): View[] {
  const byRef = new Map(previous.map(view => [view.ref, view]));
  const views = incoming.map(view => {
    const old = byRef.get(view.ref);
    return old?.revision === view.revision ? old : view;
  });
  return views.length === previous.length && views.every((view, index) => view === previous[index]) ? previous : views;
}
