import assert from "node:assert/strict";
import type { Browser } from "@playwright/test";
import type { Snapshot } from "../src/runtime/types";
import { fixture, harness, node, readyCanvas, fit } from "./browser-harness";

/** 注释实际事件和候选视觉状态；不启动服务、窗口或截图。 */
export async function checkNoteNavigation(browser: Browser) {
  const view = fixture(), elements = view.document.spec.elements;
  elements.graph.props.nodes[0].roles = ["start"];
  elements.other.props.nodes[0] = { ...elements.other.props.nodes[0], roles: ["end"], outcome: "success" };
  elements.third.props.nodes[0] = { ...elements.third.props.nodes[0], roles: ["end"], outcome: "failure" };
  elements.third.props.nodes.push({ id: "neutral", label: "中性终点", roles: ["end"] });
  const snapshot: Snapshot = { views: [view], notes: [], browse: {} };
  const h = await harness(browser, snapshot), { page } = h;
  try {
    h.release(); await page.getByText("还没有答案", { exact: true }).waitFor(); await h.publish(view.ref); await readyCanvas(page); await fit(page);
    const request = node(page, "graph", "request"), cache = node(page, "graph", "cache");
    assert.ok((await request.getAttribute("class"))?.includes("start"));
    const colors = await Promise.all([request, node(page, "other", "return"), node(page, "third", "return"), node(page, "third", "neutral")].map(n => n.evaluate(el => getComputedStyle(el).backgroundColor)));
    assert.equal(new Set(colors).size, 4, "起点和成功/失败/中性终点有不同底色：" + JSON.stringify(colors));
    assert.equal(await cache.locator(".node-branch-icon").count(), 1);
    for (const selector of [".graph-viewport-controls", ".graph-route-controls", ".graph-side-controls"]) {
      const buttons = page.locator(selector).locator("button");
      for (let i = 0; i < await buttons.count(); i++) assert.equal(await buttons.nth(i).locator("svg").count(), 1);
    }
    await request.click(); await page.waitForTimeout(300); await request.hover(); await request.locator(".node-annotation").click();
    const popup = page.locator(".annotation-dialog"), composer = page.getByLabel("问题内容");
    assert.equal(await popup.locator("h2").count(), 0);
    assert.equal(await popup.getByText(/问题·待答|来源节点|注释与追问/).count(), 0);
    await composer.fill("第一条问题"); await popup.getByRole("button", { name: "发送注释", exact: true }).click();
    await popup.waitFor({ state: "detached" }); await request.hover(); await request.locator(".node-annotation").click();
    await popup.getByText("第一条问题", { exact: true }).waitFor();
    assert.equal(await composer.inputValue(), "");
    await composer.fill("独立新草稿");
    assert.equal(await popup.getByRole("button", { name: "编辑注释", exact: true }).count(), 0);
    await popup.getByRole("button", { name: "查看详情", exact: true }).click(); await popup.waitFor({ state: "detached" });
    assert.equal(h.calls.findLast(c => c.method === "noteDetails")?.params.ref, snapshot.notes[0].ref);
    assert.equal(h.calls.filter(c => c.method === "deleteNote").length, 0);
    assert.equal(snapshot.notes[0].text, "第一条问题");
    // 原生详情完成编辑后发布新版本；网页只接收快照，不再拥有第二套编辑器。
    snapshot.notes[0] = { ...snapshot.notes[0], text: "修改问题", ref: snapshot.notes[0].ref.replace("@1", "@2") }; await h.publish();
    await request.hover(); await request.locator(".node-annotation").click(); await popup.getByText("修改问题", { exact: true }).waitFor();
    assert.equal(await composer.inputValue(), "独立新草稿");
    h.failDelete(); await popup.getByRole("button", { name: "删除注释", exact: true }).click(); await popup.getByRole("alert").waitFor();
    assert.equal(snapshot.notes.length, 1); assert.equal(await composer.inputValue(), "独立新草稿");
    await popup.getByRole("button", { name: "删除注释", exact: true }).click(); await popup.locator(".note").waitFor({ state: "detached" });
    assert.equal(snapshot.notes.length, 0); assert.equal(await composer.inputValue(), "独立新草稿");
    assert.equal(await page.getByRole("button", { name: "复制全部问题", exact: true }).isDisabled(), true);
    await page.keyboard.press("Escape"); await request.hover(); await request.locator(".node-annotation").click(); assert.equal(await composer.inputValue(), "独立新草稿");
    await popup.getByRole("button", { name: "发送注释", exact: true }).click(); await popup.waitFor({ state: "detached" });
    await request.hover(); await request.locator(".node-annotation").click(); await popup.getByText("独立新草稿", { exact: true }).waitFor();
    await composer.fill("回答期间的新草稿");
    const answered = snapshot.notes[0];
    snapshot.notes = [];
    snapshot.views.push({ ...view, ref: "view:answer", parentRef: view.ref, answeredNote: { ...answered, status: "answered", answerRef: "view:answer" }, origin: answered.origin, document: { ...view.document, title: "节点的回答", spec: { root: "text", elements: { text: { type: "Text", props: { text: "回答内容" } } } } } });
    await h.publish(); await popup.locator(".note").waitFor({ state: "detached" });
    assert.equal(await composer.inputValue(), "回答期间的新草稿");
    assert.equal(await page.getByRole("button", { name: "复制全部问题", exact: true }).isDisabled(), true);
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "理解层级", exact: true }).click();
    await page.locator(".layer-popup").getByRole("button", { name: /读取请求 的追问/ }).click(); await page.getByText("回答内容", { exact: true }).waitFor();
    await h.publish(view.ref); await readyCanvas(page); await fit(page);
    await page.keyboard.press("Escape"); await cache.click(); await page.waitForTimeout(300);
    const sourceCalls = () => h.calls.filter(c => c.method === "source").length;
    const before = sourceCalls();
    await page.getByRole("button", { name: "下一步", exact: true }).click(); await page.waitForTimeout(300);
    assert.equal(await page.locator(".route-popup").count(), 0);
    assert.equal(await page.locator(".lesson-node.selected,.react-flow__edge.selected").count(), 0);
    const a = node(page, "other", "return"), b = node(page, "third", "return");
    assert.equal(await a.getAttribute("data-attention"), "preview"); assert.equal(await b.getAttribute("data-attention"), "candidate");
    await b.hover(); assert.equal(await b.getAttribute("data-attention"), "preview"); assert.equal(sourceCalls(), before);
    const previewColor = await b.evaluate(el => getComputedStyle(el).outlineColor), candidateColor = await a.evaluate(el => getComputedStyle(el).outlineColor);
    assert.notEqual(previewColor, candidateColor);
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => document.querySelectorAll(".lesson-node[data-attention]").length === 0);
    assert.ok((await cache.getAttribute("class"))?.includes("selected"));
    await page.getByRole("button", { name: "下一步", exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.lesson-node[data-element-id="other"]')?.getAttribute("data-attention") === "preview");
    await page.keyboard.press("ArrowRight");
    await page.waitForFunction(() => document.querySelector('.lesson-node[data-element-id="third"][data-node-id="return"]')?.getAttribute("data-attention") === "preview");
    await page.keyboard.press("ArrowLeft");
    await page.waitForFunction(() => document.querySelector('.lesson-node[data-element-id="other"]')?.getAttribute("data-attention") === "preview");
    await page.keyboard.press("Enter"); await page.waitForTimeout(300);
    assert.ok((await a.getAttribute("class"))?.includes("selected")); assert.equal(await page.locator(".lesson-node[data-attention]").count(), 0);
    assert.equal(sourceCalls(), before + 1, "预览不跳源码，确认才定位");
    assert.deepEqual(h.errors, []);
  } finally { await page.close(); }
}
