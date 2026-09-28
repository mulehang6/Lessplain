import { initialFrames, projectGraph } from "./data";
import {
  initialVisit,
  validateTrace,
  type Frame,
  type SourceMode,
  type Visit,
} from "./model";
export const STORAGE_KEY = "understanding-prototype-v04";
export type Session = {
  version: 1;
  frames: Frame[];
  active: string;
  visits: Record<string, Visit>;
  mode: SourceMode;
  collapsed: string[];
  view: "question" | "project";
  projectNode: string;
};

export function freshSession(): Session {
  return {
    version: 1,
    frames: structuredClone(initialFrames),
    active: "A",
    visits: Object.fromEntries(
      initialFrames.map((frame) => [frame.id, initialVisit(frame)]),
    ),
    mode: "original",
    collapsed: [],
    view: "question",
    projectNode: "desktop",
  };
}

export function readSession(): Session {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return freshSession();
    const saved = JSON.parse(raw) as Session;
    if (
      saved.version !== 1 ||
      !Array.isArray(saved.frames) ||
      !saved.frames.some((f) => f.id === saved.active) ||
      !["original", "moved", "missing", "ambiguous"].includes(saved.mode) ||
      !["question", "project"].includes(saved.view) ||
      !Array.isArray(saved.collapsed) ||
      !projectGraph.nodes.some((n) => n.id === saved.projectNode)
    )
      return freshSession();
    const ids = new Set(saved.frames.map((frame) => frame.id));
    if (ids.size !== saved.frames.length) return freshSession();
    for (const frame of saved.frames) {
      const visit = saved.visits[frame.id];
      if (
        !frame.nodes.length ||
        !visit ||
        !frame.nodes.some((n) => n.id === visit.nodeId) ||
        !["branch", "snapshot", "strategy"].includes(visit.file) ||
        !Number.isInteger(visit.cursor) ||
        visit.cursor < -1 ||
        visit.cursor >= (frame.trace?.hits.length ?? 0) ||
        (frame.parentId !== null && !ids.has(frame.parentId))
      )
        return freshSession();
      const ancestors = new Set([frame.id]);
      let parent = frame.parentId;
      while (parent !== null) {
        if (ancestors.has(parent)) return freshSession();
        ancestors.add(parent);
        parent = saved.frames.find((f) => f.id === parent)!.parentId;
      }
      frame.nodes.forEach((n) => {
        if (!["branch", "snapshot", "strategy"].includes(n.anchor.file))
          throw new Error("Invalid anchor");
      });
      if (frame.trace) validateTrace(frame, frame.trace);
    }
    return saved;
  } catch {
    return freshSession();
  }
}
