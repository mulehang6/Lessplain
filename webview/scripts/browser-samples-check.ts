import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { expect, type Browser } from "@playwright/test";
import { clickFrameButton, fixture, fit, harness, node, readyCanvas } from "./browser-harness";
import type { View } from "../src/runtime/types";

/** 演示和静态图共享步骤，导航只携带保存后的资源 ID，不提交教学代码正文。 */
export async function checkCodeSamples(browser: Browser) {
  const document = JSON.parse(await readFile("../docs/examples/coroutine-waiting.view.json", "utf8"));
  const view: View = { ...fixture(), sources: {}, document };
  const h = await harness(browser, { views: [view], notes: [], browse: {} }), { page } = h;
  try {
    h.release(); await page.getByText("还没有答案", { exact: true }).waitFor(); await h.publish(view.ref);
    await readyCanvas(page); await fit(page);
    assert.equal(await page.locator(".react-flow").count(), 1);
    assert.equal(await page.locator(".canvas-cluster-node").count(), 2);
    assert.equal(await page.locator(".lesson-node").count(), 6);
    await page.getByRole("button", { name: /查看挂起版代码/ }).click();
    assert.equal(h.calls.findLast(c => c.method === "sample")?.params.sample, "suspend");
    assert.equal(h.calls.findLast(c => c.method === "sample")?.params.code, undefined);
    await node(page, "suspend-graph", "b").click(); await page.waitForTimeout(300);
    const navigation = h.calls.findLast(c => c.method === "sample")!;
    assert.equal(navigation.params.nodeId, "b"); assert.equal(navigation.params.startLine, 10);
    const frame = page.frameLocator('iframe[title="挂起任务与线程"]');
    assert.equal(await frame.locator("#b").innerText(), "完成");
    await fit(page);
    await clickFrameButton(page, "挂起任务与线程", "重置"); await page.waitForTimeout(300); await fit(page);
    await clickFrameButton(page, "挂起任务与线程", "推进一步"); await page.waitForTimeout(300);
    assert.equal(h.calls.findLast(c => c.method === "sample")?.params.nodeId, "b");
    assert.equal(h.calls.findLast(c => c.method === "sample")?.params.state.suspend.step, "b");
    const frameHandle = await page.locator('iframe[title="挂起任务与线程"]').evaluate(el => { (window as any).__sampleFrame = (el as HTMLIFrameElement).contentWindow; return true; });
    assert.equal(frameHandle, true);
    view.revision++; view.document = { ...view.document, title: "重命名课程" };
    await h.publish(); await page.waitForTimeout(100);
    assert.equal(await page.locator('iframe[title="挂起任务与线程"]').evaluate(el => (window as any).__sampleFrame === (el as HTMLIFrameElement).contentWindow), true);
    assert.equal(await page.frameLocator('iframe[title="挂起任务与线程"]').locator("#b").innerText(), "完成");
    const color = await page.frameLocator('iframe[title="挂起任务与线程"]').locator("body").evaluate(el => getComputedStyle(el).color);
    assert.equal(color, await page.locator("html").evaluate(el => getComputedStyle(el).color), "隔离演示沿用宿主主题");
    const originalTheme = await page.locator("html").getAttribute("style");
    for (const [background, foreground, scheme] of [["rgb(35, 36, 40)", "rgb(220, 221, 224)", "dark"], ["rgb(248, 249, 251)", "rgb(30, 32, 36)", "light"]]) {
      await page.evaluate(({ background, foreground, scheme }) => {
        document.documentElement.style.setProperty("--card", background);
        document.documentElement.style.setProperty("--foreground", foreground);
        document.documentElement.style.setProperty("--ide-color-scheme", scheme);
      }, { background, foreground, scheme });
      await expect(page.locator('iframe[title="挂起任务与线程"]')).toHaveCSS("background-color", background);
      await expect(frame.locator("html")).toHaveCSS("background-color", background);
      await expect(frame.locator("html")).toHaveCSS("color-scheme", scheme);
      await expect(frame.locator("body")).toHaveCSS("color", foreground);
      assert.equal(await frame.locator("#b").innerText(), "完成", "切换主题不能重置演示步骤");
    }
    await page.evaluate(style => {
      if (style === null) document.documentElement.removeAttribute("style");
      else document.documentElement.setAttribute("style", style);
    }, originalTheme);
    assert.equal(await page.locator('iframe[title="挂起任务与线程"]').evaluate(el => (window as any).__sampleFrame === (el as HTMLIFrameElement).contentWindow), true);
    await fit(page); await clickFrameButton(page, "阻塞任务与线程", "推进一步"); await page.waitForTimeout(300);
    assert.equal(h.calls.findLast(c => c.method === "sample")?.params.sample, "blocking");
    assert.equal(h.calls.findLast(c => c.method === "sample")?.params.nodeId, "resume");
    assert.equal(await page.frameLocator('iframe[title="阻塞任务与线程"]').locator("#b").innerText(), "待执行");
    await fit(page); await clickFrameButton(page, "阻塞任务与线程", "推进一步"); await page.waitForTimeout(300);
    assert.equal(h.calls.findLast(c => c.method === "sample")?.params.nodeId, "b");
    assert.deepEqual(h.errors, []);
  } finally { await page.close(); }
}
