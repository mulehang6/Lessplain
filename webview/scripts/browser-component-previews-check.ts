import assert from "node:assert/strict";
import { expect, type Browser } from "@playwright/test";
import { fixture, fit, harness, layoutPosts, readyCanvas, viewport } from "./browser-harness";

/** 实际 DOM 测量、Worker 提交与原生预览载荷一起验证，避免只测试尺寸缓存实现。 */
export async function checkComponentPreviews(browser: Browser) {
  const view = fixture(), elements = view.document.spec.elements;
  elements.stack.children = ["json", "fold-a", "fold-b", "accordion", "excerpt", "colored", "badge"];
  elements["fold-a"] = { type: "Collapsible", props: { title: "第一处详情", defaultOpen: false }, children: ["long-a", "nested-fold"] };
  elements["nested-fold"] = { type: "Collapsible", props: { title: "嵌套详情", defaultOpen: false }, children: ["nested-text"] };
  elements["nested-text"] = { type: "Text", props: { text: "嵌套的独立说明。".repeat(20) } };
  elements["fold-b"] = { type: "Collapsible", props: { title: "第二处详情", defaultOpen: false }, children: ["long-b"] };
  elements["long-a"] = { type: "Text", props: { text: "较长的源码说明。".repeat(65) } };
  elements["long-b"] = { type: "Text", props: { text: "另一处独立说明。".repeat(40) } };
  elements.accordion = { type: "Accordion", props: { type: "multiple", items: [{ title: "折叠项甲", content: "说明".repeat(100) }, { title: "折叠项乙", content: "另一个说明".repeat(60) }] } };
  elements.excerpt = { type: "CodeExcerpt", props: { source: "cache", label: "保存的实现" } };
  elements.colored = { type: "Card", props: { title: "结果", tone: "success" }, children: [] };
  elements.badge = { type: "Badge", props: { text: "注意边界", tone: "warning" } };
  const h = await harness(browser, { views: [view], notes: [], browse: {} }), { page } = h;
  try {
    h.release(); await page.getByText("还没有答案", { exact: true }).waitFor(); await h.publish(view.ref);
    await readyCanvas(page); await fit(page);
    const companion = page.locator('.canvas-companion-node[data-element-id="stack"]');
    const height = () => companion.evaluate(node => (node as HTMLElement).offsetHeight);
    const group = page.locator('.canvas-cluster-node[data-element-id="cluster"]').locator("..");
    const initial = await height(), camera = await viewport(page).getAttribute("style");
    const groupPositions = () => page.locator(".canvas-cluster-node").evaluateAll(elements =>
      elements.map(el => [el.getAttribute("data-element-id"), (el.parentElement as HTMLElement).style.transform]));
    const initialPositions = await groupPositions();
    const initialWidth = await companion.evaluate(el => (el as HTMLElement).offsetWidth);
    const before = await layoutPosts(page);
    await page.getByRole("button", { name: /第一处详情/ }).click();
    await expect.poll(height).toBeGreaterThan(initial);
    await page.waitForTimeout(250);
    assert.equal(await layoutPosts(page), before + 1, "一次展开只提交一次必要布局");
    assert.deepEqual(await groupPositions(), initialPositions, "展开不重排未冲突的相邻分组");
    assert.equal(await companion.evaluate(el => (el as HTMLElement).offsetWidth), initialWidth, "展开不改变逻辑宽度");
    assert.equal(await viewport(page).getAttribute("style"), camera, "展开保持镜头");
    const expandedWidth = await companion.evaluate(el => (el as HTMLElement).offsetWidth);
    await fit(page);
    const position = await group.evaluate(el => (el as HTMLElement).style.transform), zoom = await viewport(page).getAttribute("style");
    const expandedPosts = await layoutPosts(page);
    await page.getByRole("button", { name: /第一处详情/ }).click();
    await expect.poll(height).toBe(initial); await page.waitForTimeout(250);
    assert.equal(await layoutPosts(page), expandedPosts + 1, "一次收缩只提交一次必要布局");
    assert.equal(await group.evaluate(el => (el as HTMLElement).style.transform), position, "收缩锚定分组原位置");
    assert.deepEqual(await groupPositions(), initialPositions, "收缩不拉回或重排相邻分组");
    assert.equal(await viewport(page).getAttribute("style"), zoom, "收缩不跟焦");
    assert.equal(await companion.evaluate(el => (el as HTMLElement).offsetWidth), expandedWidth, "收起保持逻辑宽度");
    await fit(page);
    await page.getByRole("button", { name: /第二处详情/ }).click(); await page.waitForTimeout(250); await fit(page);
    const second = await height();
    await page.getByRole("button", { name: /第一处详情/ }).click(); await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /第二处详情/ }).click(); await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /第一处详情/ }).click(); await expect.poll(height).toBe(initial);
    assert.ok(second > initial);
    await fit(page);
    await page.getByRole("button", { name: /第一处详情/ }).click(); await page.waitForTimeout(250); await fit(page);
    const outerHeight = await height();
    await page.getByRole("button", { name: /嵌套详情/ }).click(); await expect.poll(height).toBeGreaterThan(outerHeight);
    await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /嵌套详情/ }).click(); await expect.poll(height).toBe(outerHeight);
    await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /第一处详情/ }).click(); await expect.poll(height).toBe(initial);
    await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /折叠项甲/ }).click(); await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /折叠项乙/ }).click(); await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /折叠项甲/ }).click(); await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /折叠项乙/ }).click(); await expect.poll(height).toBe(initial);
    await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /共享状态/ }).click();
    const frozen = h.calls.findLast(call => call.method === "componentCode")!;
    assert.equal(frozen.params.value.selection, "request");
    await page.getByRole("button", { name: /保存的实现/ }).click();
    assert.equal(h.calls.findLast(call => call.method === "componentCode")!.params.elementId, "excerpt");
    await page.getByRole("button", { name: "定位当前源码 ↗", exact: true }).click();
    assert.equal(h.calls.findLast(call => call.method === "source")!.params.source, "cache");
    for (const scheme of ["light", "dark"]) {
      await page.evaluate(scheme => document.documentElement.style.setProperty("--ide-color-scheme", scheme), scheme);
      const colors = await page.locator('.semantic-card[data-tone="success"]>*').evaluate(el => {
        const css = getComputedStyle(el); return [css.backgroundColor, css.borderColor, css.color];
      });
      assert.notEqual(colors[0], colors[1]); assert.notEqual(colors[0], colors[2]);
    }
    elements.accordion.props.type = "single"; view.revision++;
    await h.publish(); await page.waitForTimeout(300); await fit(page);
    await page.getByRole("button", { name: /折叠项甲/ }).click(); await page.waitForTimeout(250); await fit(page);
    await page.getByRole("button", { name: /折叠项乙/ }).click(); await page.waitForTimeout(250); await fit(page);
    assert.equal(await page.getByRole("button", { name: /折叠项甲/ }).getAttribute("aria-expanded"), "false");
    assert.equal(await page.getByRole("button", { name: /折叠项乙/ }).getAttribute("aria-expanded"), "true");
    await page.getByRole("button", { name: /折叠项甲/ }).focus(); await page.keyboard.press("ArrowDown");
    await expect(page.getByRole("button", { name: /折叠项乙/ })).toBeFocused();
    await page.keyboard.press("Home"); await expect(page.getByRole("button", { name: /折叠项甲/ })).toBeFocused();
    await page.evaluate(() => {
      const box = document.createElement("div"); box.id = "native-scroll";
      box.style.cssText = "height:50px;overflow:auto;width:150px";
      const text = document.createElement("div"); text.style.height = "400px"; text.textContent = "控件内滚动";
      box.append(text); document.querySelector(".canvas-companion-content")!.append(box);
    });
    await page.waitForTimeout(250); await fit(page);
    const native = page.locator("#native-scroll"), box = (await native.boundingBox())!;
    const nativeCamera = await viewport(page).getAttribute("style");
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2); await page.mouse.wheel(0, 80);
    await expect.poll(() => native.evaluate(el => el.scrollTop)).toBeGreaterThan(0);
    assert.equal(await viewport(page).getAttribute("style"), nativeCamera, "控件消费滚轮时画布不抢滚动");
    await native.evaluate(el => (el.firstElementChild as HTMLElement).style.width = "600px");
    await page.mouse.wheel(80, 0);
    await expect.poll(() => native.evaluate(el => el.scrollLeft)).toBeGreaterThan(0);
    assert.equal(await viewport(page).getAttribute("style"), nativeCamera, "原生横向区域优先消费横向输入");
    assert.equal(await native.evaluate(el => {
      const event = new WheelEvent("wheel", { deltaY: 40, shiftKey: true, bubbles: true, cancelable: true });
      el.dispatchEvent(event); return event.defaultPrevented;
    }), false, "Shift 滚轮仍交给可滚动的原生横向区域");
    assert.equal(await native.evaluate(el => {
      el.style.overflowX = "hidden"; el.scrollTop = 100;
      const event = new WheelEvent("wheel", { deltaX: 20, deltaY: 40, shiftKey: true, bubbles: true, cancelable: true });
      el.dispatchEvent(event); return event.defaultPrevented;
    }), false, "双轴 Shift 的纵向分量仍可由原生区域消费");
    assert.deepEqual(h.errors, []);
  } finally { await page.close(); }
}

/** 历史页也应发送有界心跳；不需要先进入图页或等待卡死后打开 DevTools。 */
export async function checkDiagnosticBatches(browser: Browser) {
  const h = await harness(browser, { views: [], notes: [], browse: {} }, false, true);
  try {
    h.release(); await h.page.getByText("还没有答案", { exact: true }).waitFor();
    await expect.poll(() => h.calls.filter(call => call.method === "diagnosticBatch").length, { timeout: 6000 }).toBeGreaterThan(0);
    const batch = h.calls.findLast(call => call.method === "diagnosticBatch")!.params;
    assert.equal(batch.visible, true);
    assert.ok(batch.events.some((event: { kind: string }) => event.kind === "page-ready"));
    assert.ok(batch.events.length <= 128);
    for (const event of batch.events) for (const [key, value] of Object.entries(event))
      assert.ok(key === "kind" ? typeof value === "string" : typeof value === "number");
    assert.deepEqual(h.errors, []);
  } finally { await h.page.close(); }
}
