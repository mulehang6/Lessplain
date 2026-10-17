import { create } from "zustand";
import { zustandStateStore } from "@json-render/zustand";
import type { Origin, Snapshot, View } from "./types";
import { call } from "./bridge";
import { annotationKey } from "./questions";
import { readPointer, updatePointer } from "./pointer";
import { mergeWorkspaceBrowseState, type WorkspaceBrowsePatch } from "../components/WorkspaceBrowseState";
import type { PopupAnchor } from "../components/popupGeometry";
import { shareViews } from "./snapshot";
export { readPointer } from "./pointer";

export type AnnotationDraft = { origin: Origin; text: string; kind: "question" | "feedback" };

type State = {
  snapshot: Snapshot; sequence: number; selected?: string; elementId?: string; nodeId?: string; sourceId?: string; previewSourceId?: string;
  lesson: Record<string, unknown>; error?: string;
  drafts: Record<string, AnnotationDraft>; annotation?: string; annotationAnchor?: PopupAnchor; annotationMode?: "review" | "input";
  select(ref?: string): void; receive(snapshot: Snapshot): void;
  focus(elementId?: string, nodeId?: string, sourceId?: string): void;
  setError(error?: string): void;
};
const empty: Snapshot = { views: [], notes: [], browse: {} };
export const useApp = create<State>((set, get) => ({
  snapshot: empty, lesson: {}, sequence: 0, drafts: {},
  select: ref => {
    const view = get().snapshot.views.find(v => v.ref === ref);
    const saved = ref ? get().snapshot.browse[ref] : undefined;
    const state = saved?.lesson;
    set({ selected: ref, annotation: undefined, annotationAnchor: undefined, elementId: undefined, nodeId: undefined, sourceId: undefined, previewSourceId: undefined,
      lesson: (state as Record<string, unknown> | undefined) ?? view?.document.spec.state ?? {} });
    void call("select", { ref: ref ?? null }).catch(error => set({ error: error.message }));
    if (view) saveGraph("seenRevision", view.revision);
  },
  receive: snapshot => {
    snapshot = { ...snapshot, views: shareViews(get().snapshot.views, snapshot.views) };
    const old = get().snapshot.views.find(v => v.ref === get().selected);
    const next = snapshot.views.find(v => v.ref === get().selected);
    const contentChanged = old?.revision !== next?.revision && JSON.stringify([old?.document.spec, old?.document.demos, old?.document.codeSamples, old?.sources])
      !== JSON.stringify([next?.document.spec, next?.document.demos, next?.document.codeSamples, next?.sources]);
    set({ snapshot, ...(contentChanged ? {
      lesson: next?.document.spec.state ?? {},
      elementId: undefined, nodeId: undefined, sourceId: undefined, previewSourceId: undefined,
    } : {}) });
  },
  focus: (elementId, nodeId, sourceId) => set({ elementId, nodeId, sourceId, previewSourceId: sourceId ?? get().previewSourceId }),
  setError: error => set({ error }),
}));
// json-render 与应用共享这一 slice，不另外维护 Renderer 内部状态。
export const lessonStore = zustandStateStore({
  store: useApp,
  selector: state => state.lesson,
  updater: (lesson, store) => store.setState({ lesson }),
});
/** Read from the lesson state using an RFC 6901 JSON Pointer. */
export function getLessonValueAtPointer(pointer: string): unknown {
  return readPointer(useApp.getState().lesson, pointer);
}

/** Update the shared lesson slice immutably; return true only when a value changed. */
export function setLessonValueAtPointer(pointer: string, value: unknown): boolean {
  const update = updatePointer(useApp.getState().lesson, pointer, value);
  if (!update?.changed) return false;
  useApp.setState({ lesson: update.value as Record<string, unknown> });
  return true;
}

export const currentView = (): View | undefined => {
  const state = useApp.getState(); return state.snapshot.views.find(v => v.ref === state.selected);
};
export function origin(entry?: Origin["entry"]): Origin {
  const s = useApp.getState(), view = currentView();
  const graph = s.elementId ? view?.document.spec.elements[s.elementId]?.props.nodes : undefined;
  const node = Array.isArray(graph) ? graph.find(n => n.id === s.nodeId) : undefined;
  const capturedEntry = entry ?? (s.nodeId ? "node" : "component");
  return { entry: capturedEntry, viewRef: s.selected, elementId: s.elementId, nodeId: s.nodeId,
    nodeLabel: node?.label,
    ...(capturedEntry === "sourceSelection" && s.sourceId ? { source: view?.sources[s.sourceId] } : {}), state: structuredClone(s.lesson) };
}

export function beginAnnotation(captured = origin(), anchor?: PopupAnchor) {
  const fixed: Origin = { ...captured, entry: captured.entry ?? (captured.source ? "sourceSelection" : captured.nodeId ? "node" : "component") };
  const key = annotationKey(fixed), state = useApp.getState();
  const draft = state.drafts[key] ?? { origin: structuredClone(fixed), text: "", kind: "question" };
  useApp.setState({ annotation: key, annotationAnchor: anchor, annotationMode: "input", drafts: { ...state.drafts, [key]: draft } });
}
export function updateAnnotation(change: Partial<Pick<AnnotationDraft, "text" | "kind">>) {
  const { annotation, drafts } = useApp.getState();
  if (annotation) useApp.setState({ drafts: { ...drafts, [annotation]: { ...drafts[annotation], ...change } } });
}
export function closeAnnotation(savedKey?: string) {
  const { annotation, drafts } = useApp.getState();
  if (savedKey) {
    const remaining = { ...drafts }; delete remaining[savedKey];
    useApp.setState({ drafts: remaining, ...(annotation === savedKey ? { annotation: undefined, annotationAnchor: undefined } : {}) });
  } else useApp.setState({ annotation: undefined, annotationAnchor: undefined });
}
let timer: ReturnType<typeof setTimeout> | undefined;
useApp.subscribe((state, previous) => {
  if (state.lesson === previous.lesson && state.elementId === previous.elementId && state.nodeId === previous.nodeId && state.sourceId === previous.sourceId) return;
  clearTimeout(timer);
  timer = setTimeout(() => {
    const s = useApp.getState();
    void call("origin", origin()).catch(() => {});
    if (s.selected) {
      const browse = s.snapshot.browse[s.selected] ?? {};
      void call("browse", { ref: s.selected, state: { ...browse, lesson: s.lesson } }).catch(() => {});
    }
  }, 200);
});
export function saveGraph(key: string, value: unknown) {
  const state = useApp.getState(), ref = state.selected;
  if (!ref) return;
  const browse = { ...state.snapshot.browse[ref], [key]: value, lesson: state.lesson };
  useApp.setState({ snapshot: { ...state.snapshot, browse: { ...state.snapshot.browse, [ref]: browse } } });
  void call("browse", { ref, state: browse }).catch(error => state.setError(error.message));
}

/** Save workspace transform or active-group state under the owning view's browse entry. */
export function saveWorkspaceState(ref: string | undefined, key: string, patch: WorkspaceBrowsePatch) {
  if (!ref) return;
  const state = useApp.getState();
  const saved = state.snapshot.browse[ref] ?? {};
  const browse = {
    ...saved,
    [key]: mergeWorkspaceBrowseState(saved[key], patch),
    ...(state.selected === ref ? { lesson: state.lesson } : {}),
  };
  useApp.setState({ snapshot: { ...state.snapshot, browse: { ...state.snapshot.browse, [ref]: browse } } });
  void call("browse", { ref, state: browse }).catch(error => state.setError(error.message));
}
