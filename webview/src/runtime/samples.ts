import { call } from "./bridge";
import { currentView, useApp } from "./store";
import type { CodeSampleTarget } from "./types";

/** 教学代码只按已保存资源 ID 导航，不把浏览器传来的文本写成文件。 */
export function openCodeSample(target: CodeSampleTarget, elementId?: string, nodeId?: string) {
  const view = currentView();
  if (!view?.document.codeSamples?.[target.id]) return;
  const state = useApp.getState();
  state.focus(elementId, nodeId);
  void call("sample", { ref: view.ref, revision: view.revision, sample: target.id,
    startLine: target.startLine ?? 1, ...(target.endLine === undefined ? {} : { endLine: target.endLine }),
    elementId, nodeId, state: structuredClone(state.lesson) })
    .catch(error => useApp.getState().setError(error instanceof Error ? error.message : String(error)));
}
