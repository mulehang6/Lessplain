import { afterEach, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { act } from "react";
import type { Root } from "react-dom/client";
import App from "./App";
import { currentSources, initialFrames } from "./data";
import { resolveAnchor } from "./model";
import { updatedBranchScene } from "./scenario-model";

const dom = new JSDOM('<div id="root"></div>', {
  url: "https://prototype.test",
});
Object.defineProperties(globalThis, {
  window: { value: dom.window, configurable: true },
  document: { value: dom.window.document, configurable: true },
  localStorage: { value: dom.window.localStorage, configurable: true },
  navigator: { value: dom.window.navigator, configurable: true },
  IS_REACT_ACT_ENVIRONMENT: { value: true, configurable: true },
});
dom.window.HTMLElement.prototype.scrollIntoView = function () {};
dom.window.HTMLDialogElement.prototype.showModal = function () {
  this.setAttribute("open", "");
};
dom.window.HTMLDialogElement.prototype.close = function () {
  this.removeAttribute("open");
};
const { createRoot } = await import("react-dom/client");
let root: Root;
beforeEach(async () => {
  localStorage.clear();
  document.body.innerHTML = '<div id="root"></div>';
  root = createRoot(document.getElementById("root")!);
  await act(async () => {
    root.render(<App />);
  });
});
afterEach(async () => {
  await act(async () => root.unmount());
});
function element<T extends Element = HTMLElement>(selector: string): T {
  const found = document.querySelector<T>(selector);
  assert.ok(found, `Missing ${selector}`);
  return found;
}
async function click(text: string, selector = "button") {
  const found = [
    ...document.querySelectorAll<HTMLButtonElement>(selector),
  ].find((node) => node.textContent?.includes(text));
  assert.ok(found, `Missing button ${text}`);
  assert.equal(found.disabled, false);
  await act(async () => found.click());
}
async function clickLabel(label: string) {
  await act(async () =>
    element<HTMLButtonElement>(`button[aria-label="${label}"]`).click(),
  );
}
async function scene(title: string) {
  await click("体验场景");
  await click(title, ".scene-options button");
}

test("replay controls live inside graph and repeated hits switch values on the same node", async () => {
  await scene("看三次重试");
  assert.ok(element(".graph-workspace .graph-playback"));
  assert.match(
    element(".graph-card-node.current .node-values").textContent!,
    /timeout/,
  );
  await clickLabel("尝试保存第 3 次命中");
  assert.match(
    element(".graph-card-node.current .node-values").textContent!,
    /ok/,
  );
  await clickLabel("尝试保存第 1 次命中");
  assert.match(
    element(".graph-card-node.current .node-values").textContent!,
    /timeout/,
  );
  assert.doesNotMatch(element(".code-editor").textContent!, /busy/);
  assert.equal(document.querySelector(".trace-panel"), null);
  assert.equal(document.querySelector(".event-list"), null);
});

test("backward replay and changing questions preserve each frame's cursor", async () => {
  await scene("看三次重试");
  await clickLabel("尝试保存第 2 次命中");
  await click("从历史节点", ".question-title");
  await click("重试三次", ".question-title");
  assert.match(
    element(".graph-card-node.current .node-values").textContent!,
    /busy/,
  );
  await clickLabel("上一步");
  assert.match(
    element(".graph-card-node.current .node-title").textContent!,
    /等待后重试/,
  );
  await click("全部路径", ".graph-playback button");
  assert.equal(document.querySelector(".node-values"), null);
});

test("selecting graph and source works both ways without a duplicated inspector", async () => {
  await click("创建子会话", ".graph-node");
  assert.match(element(".code-line.selected").textContent!, /val child/);
  await act(async () =>
    element<HTMLButtonElement>('button[aria-label^="第 17 行"]').click(),
  );
  assert.match(element(".graph-card-node.selected").textContent!, /安排持久化/);
  assert.equal(document.querySelector(".node-inspector"), null);
});

test("changed code exposes a comparison and a copyable request instead of a dead-end warning", async () => {
  await scene("代码改了以后");
  assert.equal(document.querySelector(".code-line.selected"), null);
  await click("查看变化", ".node-change-action");
  assert.match(element(".old-code").textContent!, /Conversation/);
  assert.match(element(".new-code").textContent!, /buildBranch/);
  await click("复制核对问题");
  assert.match(
    element<HTMLTextAreaElement>(".copy-dialog textarea").value,
    /更新对应图节点/,
  );
  assert.equal(document.querySelector(".change-dialog"), null);
});

test("receiving prepared update changes the anchor but preserves old trace evidence", async () => {
  await scene("代码改了以后");
  await click("收到更新后的图");
  assert.match(
    element(".graph-card-node.selected .node-detail").textContent!,
    /buildBranch/,
  );
  assert.match(element(".code-line.selected").textContent!, /buildBranch/);
  assert.equal(document.querySelector(".node-change-action"), null);
  await click("回放", ".graph-playback button");
  assert.match(element(".canvas-corner").textContent!, /旧代码的回放/);
  const updated = updatedBranchScene();
  assert.equal(updated.trace!.revision, initialFrames[0].trace!.revision);
  assert.equal(updated.trace!.hits[2].observation.revision, "demo-r1");
  assert.equal(
    resolveAnchor(updated.nodes[2].anchor, currentSources("missing")[0])
      .evidenceStale,
    false,
  );
});

test("followup scene shows the actual question and returns to its originating trace step", async () => {
  await scene("继续追问草稿");
  assert.match(element(".story-question").textContent!, /草稿怎样/);
  assert.doesNotMatch(
    element(".app-shell").textContent!,
    /冻结当前上下文|显式同级|显式根问题|默认子问题/,
  );
  await click("看 Agent 的回答");
  assert.match(element(".parent-breadcrumb").textContent!, /从历史节点/);
  const saved = JSON.parse(
    localStorage.getItem("understanding-prototype-v04")!,
  );
  assert.equal(
    saved.frames.find((f: { id: string }) => f.id === "B").parentId,
    "A",
  );
  await click("回到提问处");
  assert.match(
    element(".graph-card-node.current .node-title").textContent!,
    /切换会话/,
  );
  assert.match(element(".graph-playback .mono").textContent!, /4 \/ 5/);
});

test("project map still connects evidence and source, and refresh restores selection", async () => {
  await click("查看项目关系图");
  await click("Agent Runtime", ".graph-node");
  assert.match(element(".file-tabs .active").textContent!, /AgentExample/);
  assert.match(element(".project-evidence").textContent!, /shared \/ agent/);
  await act(async () => root.unmount());
  root = createRoot(document.getElementById("root")!);
  await act(async () => root.render(<App />));
  assert.match(
    element(".graph-card-node.selected").textContent!,
    /Agent Runtime/,
  );
});

test("legacy missing and ambiguous anchors remain non-navigable after reload", async () => {
  for (const mode of ["moved", "missing", "ambiguous"]) {
    const saved = JSON.parse(
      localStorage.getItem("understanding-prototype-v04")!,
    );
    saved.mode = mode;
    saved.active = "A";
    saved.view = "question";
    saved.visits.A = { nodeId: "child", cursor: -1, file: "branch" };
    await act(async () => root.unmount());
    localStorage.setItem("understanding-prototype-v04", JSON.stringify(saved));
    root = createRoot(document.getElementById("root")!);
    await act(async () => root.render(<App />));
    if (mode === "moved")
      assert.match(
        element(".code-line.selected .line-number").textContent!,
        /10/,
      );
    else assert.equal(document.querySelector(".code-line.selected"), null);
    assert.ok(document.querySelector(".change-link"));
  }
});
