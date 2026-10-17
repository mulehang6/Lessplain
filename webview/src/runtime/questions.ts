import type { Note, Origin, Snapshot } from "./types";

/** 从任意追问回到根，收集这棵理解树；独立源码问题在图内一并交接。 */
export function scopedQuestions(snapshot: Snapshot, ref: string): Note[] {
  const views = new Map(snapshot.views.map(view => [view.ref, view]));
  const ancestors = new Set<string>();
  let root = ref;
  while (views.get(root)?.parentRef && !ancestors.has(root)) {
    ancestors.add(root); root = views.get(root)!.parentRef!;
  }
  const scope = new Set([root]);
  let changed = true;
  while (changed) {
    changed = false;
    snapshot.views.forEach(view => { if (view.parentRef && scope.has(view.parentRef) && !scope.has(view.ref)) { scope.add(view.ref); changed = true; } });
  }
  return snapshot.notes.filter(note => note.ownerRef === undefined || scope.has(note.ownerRef));
}

/** 剪贴板只带问题原文和引用，来源、源码与示例状态由 Agent 通过引用读取。 */
export function pendingQuestions(notes: Note[]): Note[] {
  return notes.filter(note => note.status === "pending").sort((a, b) => a.createdAt - b.createdAt);
}
export function questionsForAgent(notes: Note[]): string {
  const pending = pendingQuestions(notes);
  if (!pending.length) return "";
  return "请处理以下待答问题，通过 Lessplain 提交对应回复：\n\n" + pending.map((note, index) =>
    (index + 1) + ". " + (note.kind === "feedback" ? "反馈：" : "") + note.text + "\n   引用：" + note.ref,
  ).join("\n\n");
}

/** 同一来源再次打开时恢复草稿；草稿来源和状态保持首次捕获的快照。 */
export function annotationKey(origin: Origin): string {
  return JSON.stringify([origin.entry, origin.viewRef, origin.elementId, origin.nodeId, origin.source, origin.sample]);
}
