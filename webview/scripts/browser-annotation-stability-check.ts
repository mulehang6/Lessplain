import assert from "node:assert/strict";
import type { Browser } from "@playwright/test";
import { fixture, fit, harness, layoutPosts, node, readyCanvas } from "./browser-harness";
import type { Note, Snapshot } from "../src/runtime/types";

/** 暂停后续布局回复，逐帧捕捉元数据刷新；不能靠稍后的 ELK 修正掩盖中间跳动。 */
export async function checkAnnotationStability(browser: Browser) {
  const view = fixture(), snapshot: Snapshot = { views: [view], notes: [], browse: {} };
  view.document.spec.elements.graph.props.edges[0] = { id: "lookup", source: "request", target: "cache", kind: "call", summary: "缓存调用的按需说明" };
  const h = await harness(browser, snapshot), { page } = h;
  try {
    h.release(); await page.getByText("还没有答案", { exact: true }).waitFor();
    await h.publish(view.ref); await readyCanvas(page); await fit(page);
    const request = node(page, "graph", "request");
    assert.equal(await page.locator(".edge-label").count(), 2, "summary 不自动产生关系类型标签");
    await page.keyboard.press("Tab");
    await page.getByLabel("缓存调用的按需说明", { exact: true }).focus();
    await page.getByRole("tooltip", { name: "缓存调用的按需说明", exact: true }).waitFor();
    await page.keyboard.press("Escape");
    assert.equal(await page.getByRole("tooltip").count(), 0);
    await request.click(); await page.waitForTimeout(300);
    const open = async () => {
      await request.hover();
      const button = request.locator(".node-annotation");
      await button.hover();
      const hit = await button.evaluate(el => {
        const r = el.getBoundingClientRect(), x = r.x + r.width / 2, y = r.y + r.height / 2;
        return { x, y, hit: el.contains(document.elementFromPoint(x, y)), target: document.elementFromPoint(x, y)?.outerHTML };
      });
      assert.ok(hit.hit, JSON.stringify(hit));
      await page.mouse.click(hit.x, hit.y);
      await page.getByLabel("问题内容").waitFor();
    };
    await open();
    const popup = page.locator(".annotation-dialog"), input = page.getByLabel("问题内容");
    assert.equal(await popup.locator("h2,.annotation-kind").count(), 0);
    const send = popup.getByRole("button", { name: "发送注释", exact: true });
    assert.equal(await send.isDisabled(), true);
    await input.fill("稳定性问题");
    await input.hover();
    assert.equal(await send.evaluate(el => getComputedStyle(el).backgroundColor), "rgba(0, 0, 0, 0)");
    await send.hover();
    assert.notEqual(await send.evaluate(el => getComputedStyle(el).backgroundColor), "rgba(0, 0, 0, 0)");
    const before = await layoutPosts(page);
    await page.evaluate(`(() => {
      const w = window;
      w.__holdLayouts = true;
      w.__samples = [];
      w.__sampling = true;
      const geometry = () => JSON.stringify({
        viewport: document.querySelector(".react-flow__viewport").getAttribute("style"),
        nodes: [...document.querySelectorAll(".react-flow__node")].map(el => ({
          id: el.dataset.id, style: el.getAttribute("style"), width: el.offsetWidth, height: el.offsetHeight,
        })),
        edges: [...document.querySelectorAll(".react-flow__edge-path")].map(el => el.getAttribute("d")),
      });
      const sample = () => { w.__samples.push(geometry()); if (w.__sampling) requestAnimationFrame(sample); };
      sample();
    })()`);
    await send.click(); await popup.waitFor({ state: "detached" });
    assert.equal(snapshot.notes[0].kind, "question");
    await open(); assert.equal(await input.inputValue(), "");
    await popup.getByRole("button", { name: "查看详情", exact: true }).click(); await popup.waitFor({ state: "detached" });
    snapshot.notes[0] = { ...snapshot.notes[0], text: "修改后问题", ref: snapshot.notes[0].ref.replace("@1", "@2") }; await h.publish();
    await open();
    await popup.getByText("修改后问题", { exact: true }).waitFor();
    await input.fill("独立草稿");
    await popup.getByRole("button", { name: "删除注释", exact: true }).click();
    await popup.locator(".note").waitFor({ state: "detached" });
    assert.equal(await input.inputValue(), "独立草稿");
    // 精确节点来源的待答源码问题进入同一节点列表；没有节点归属或属于其他元素的源码问题不能混入。
    const sourceNote: Note = {
      ref: "note:source-node@1", kind: "question", text: "源码选区问题", status: "pending", createdAt: 2,
      origin: { entry: "sourceSelection", viewRef: view.ref, elementId: "graph", nodeId: "request", nodeLabel: "读取请求",
        source: { path: "ChatRunController.kt", text: "with", line: 87, version: "fixture",
          selectionRange: { startOffset: 3274, endOffset: 3278 } }, state: {} },
    };
    snapshot.notes.push(sourceNote,
      { ...sourceNote, ref: "note:source-unowned@1", text: "无节点归属的源码问题", ownerRef: undefined,
        origin: { entry: "sourceSelection", viewRef: view.ref, source: sourceNote.origin.source, state: {} } },
      { ...sourceNote, ref: "note:source-other-element@1", text: "其他元素的源码问题", ownerRef: undefined,
        origin: { ...sourceNote.origin, elementId: "other" } });
    await h.publish();
    const annotation = request.locator(".node-annotation");
    assert.equal(await annotation.getAttribute("data-pending-count"), "1");
    assert.equal(await annotation.getAttribute("data-followup-count"), "0");
    assert.equal(await annotation.locator("sup").textContent(), "1");
    assert.equal(await popup.locator(".note").count(), 1, "节点列表仅显示精确归属的源码问题");
    await popup.getByText("源码选区问题", { exact: true }).waitFor();
    assert.equal(await popup.getByText("无节点归属的源码问题", { exact: true }).count(), 0);
    assert.equal(await popup.getByText("其他元素的源码问题", { exact: true }).count(), 0);

    // 回答页替代已回答注释的徽标计数；同一来源只计一次。
    const answeredNote: Note = { ...sourceNote, status: "answered", answerRef: "view:followup-stable" };
    snapshot.notes[0] = answeredNote;
    snapshot.views.push({ ...view, ref: "view:followup-stable", parentRef: view.ref, answeredNote,
      origin: { viewRef: view.ref, elementId: "graph", nodeId: "request", state: {} } });
    await h.publish(); await page.waitForTimeout(300);
    assert.equal(await annotation.getAttribute("data-pending-count"), "0");
    assert.equal(await annotation.getAttribute("data-followup-count"), "1");
    assert.equal(await annotation.locator("sup").textContent(), "1", "回答页和已回答注释不能重复计数");
    assert.equal(await popup.locator(".note").count(), 0, "节点待答列表隐藏已回答的问题");
    const samples = await page.evaluate(() => { (window as any).__sampling = false; return (window as any).__samples as string[]; });
    assert.ok(samples.length > 10);
    assert.equal(new Set(samples).size, 1, "增删改注释及追问标记的每一帧几何都应相同");
    assert.equal(await layoutPosts(page), before, "元数据更新不得提交新的布局任务，即使 Worker 回复被暂停");
    await page.keyboard.press("Escape"); await popup.waitFor({ state: "detached" });
    await open(); assert.equal(await input.inputValue(), "独立草稿");
    await page.mouse.click(1000, 30); await popup.waitFor({ state: "detached" });
    await open(); assert.equal(await input.inputValue(), "独立草稿");
    await popup.getByRole("button", { name: "取消", exact: true }).click(); await popup.waitFor({ state: "detached" });
    await open(); assert.equal(await input.inputValue(), "", "取消清空新草稿，外部关闭和 Escape 保留");
    assert.deepEqual(h.errors, []);
  } finally { await page.close(); }
}
