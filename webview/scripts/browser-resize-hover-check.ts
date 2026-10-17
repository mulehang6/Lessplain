import assert from "node:assert/strict";
import type { Browser } from "@playwright/test";
import { fixture, fit, harness, layoutPosts, node, readyCanvas, viewport } from "./browser-harness";

/** 使用真实命中、尺寸事件及 CSS 合成检查，禁止强制点击或截图。 */
export async function checkResizeAndHover(browser: Browser) {
  const view = fixture();
  delete view.document.spec.elements.graph.props.direction;
  const h = await harness(browser, { views: [view], notes: [], browse: {} }), { page } = h;
  try {
    h.release(); await page.getByText("还没有答案", { exact: true }).waitFor();
    await h.publish(view.ref); await readyCanvas(page); await fit(page);
    for (const target of [node(page, "graph", "request"), node(page, "graph", "cache"), node(page, "graph", "request")]) {
      await target.hover();
      await page.waitForFunction(() => document.querySelectorAll(".graph-node-actions.is-visible").length === 1);
      assert.ok((await target.locator(".graph-node-actions").getAttribute("class"))?.includes("is-visible"));
    }
    await page.mouse.move(1000, 30);
    await page.waitForFunction(() => document.querySelectorAll(".graph-node-actions.is-visible").length === 0);
    assert.equal(await page.locator(".graph-node-actions").first().evaluate(el => getComputedStyle(el).transitionDuration), "0s");
    const request = node(page, "graph", "request");
    await request.locator(".node-annotation").focus();
    assert.equal(await page.locator(".graph-node-actions.is-visible").count(), 1);
    await request.locator(".source-copy-button").focus();
    assert.equal(await page.locator(".graph-node-actions.is-visible").count(), 1);
    const before = await layoutPosts(page), camera = await viewport(page).getAttribute("style");
    const geometry = await page.locator(".react-flow__node").evaluateAll(nodes => nodes.map(el => el.getAttribute("style")));
    for (const width of [820, 790, 810, 780]) {
      await page.setViewportSize({ width, height: 780 }); await page.waitForTimeout(35);
      assert.equal(await layoutPosts(page), before, "窗口连续调整期间不提交ELK");
      assert.deepEqual(await page.locator(".react-flow__node").evaluateAll(nodes => nodes.map(el => el.getAttribute("style"))), geometry);
    }
    await page.waitForTimeout(500);
    assert.equal(await layoutPosts(page), before, "跨断点停稳后仍保留布局方向和组件逻辑宽度");
    assert.equal(await viewport(page).getAttribute("style"), camera, "resize 不自动调整镜头");
    const settled = await layoutPosts(page);
    await page.setViewportSize({ width: 740, height: 780 }); await page.waitForTimeout(450);
    assert.equal(await layoutPosts(page), settled, "纯图同方向宽度变化不重排");
    await page.getByRole("button", { name: "一键自动排版", exact: true }).click();
    await page.waitForFunction(count => (window as unknown as { __layoutPosts: number }).__layoutPosts > count, settled);
    await page.waitForTimeout(500);
    const arranged = await layoutPosts(page);
    await page.setViewportSize({ width: 1100, height: 780 }); await page.waitForTimeout(250);
    assert.equal(await layoutPosts(page), arranged, "显式排版后的宽度和 AUTO 方向继续保持");
    for (const theme of [
      { "--background": "#1e1f22", "--card": "#1e1f22", "--foreground": "#dfe1e5", "--control-hover": "rgba(255,255,255,0.12)", "--control-pressed": "rgba(255,255,255,0.18)" },
      { "--background": "#f7f8fa", "--card": "#f7f8fa", "--foreground": "#252932", "--control-hover": "rgba(0,0,0,0.10)", "--control-pressed": "rgba(0,0,0,0.18)" },
    ]) {
      await page.evaluate(theme => window.__lessplainReceive?.({ theme }), theme);
      const zoom = page.getByRole("button", { name: "放大", exact: true }); await zoom.hover();
      const bg = await zoom.evaluate(el => getComputedStyle(el).backgroundColor);
      assert.ok(bg.startsWith("rgba("), bg);
      const composite = await zoom.evaluate(el => {
        const probe = document.createElement("canvas"), context = probe.getContext("2d")!;
        context.fillStyle = getComputedStyle(document.documentElement).getPropertyValue("--card"); context.fillRect(0, 0, 1, 1);
        context.fillStyle = getComputedStyle(el).backgroundColor; context.fillRect(0, 0, 1, 1);
        return [...context.getImageData(0, 0, 1, 1).data];
      });
      assert.notDeepEqual(composite, [255, 255, 255, 255], "主题 hover 合成后不能是纯白");
    }
    assert.deepEqual(h.errors, []);
  } finally { await page.close(); }
}
