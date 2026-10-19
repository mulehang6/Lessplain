import assert from "node:assert/strict";
import type { Browser } from "@playwright/test";
import { fixture, fit, harness, layoutPosts, readyCanvas, viewport } from "./browser-harness";

/** 同任务内连续双轴输入直接累加，不排队补走；诊断保留最近手势而非仅首批样本。 */
export async function checkDiagonalWheel(browser: Browser) {
  const view = fixture(), h = await harness(browser, { views: [view], notes: [], browse: {} }, false, true);
  const { page } = h;
  const position = () => viewport(page).evaluate(el => {
    const transform = new DOMMatrix(getComputedStyle(el).transform);
    return { x: transform.e, y: transform.f, zoom: transform.a };
  });
  try {
    h.release(); await page.getByText("还没有答案", { exact: true }).waitFor();
    await h.publish(view.ref); await readyCanvas(page); await fit(page);
    const before = await position(), posts = await layoutPosts(page);
    await page.evaluate(() => {
      window.__lessplainDiagnostics!.clear();
      const pane = document.querySelector(".react-flow__pane")!;
      for (let i = 0; i < 180; i++) pane.dispatchEvent(new WheelEvent("wheel", {
        deltaX: i < 120 ? 2 : -1, deltaY: i < 120 ? 1 : -1,
        shiftKey: true, bubbles: true, cancelable: true,
      }));
    });
    const after = await position();
    assert.ok(Math.abs(after.x - (before.x - 180)) < 0.01);
    assert.ok(Math.abs(after.y - (before.y - 60)) < 0.01);
    assert.equal(after.zoom, before.zoom);
    await page.waitForTimeout(350);
    assert.deepEqual(await position(), after, "最后一个输入后不产生额外位移");
    assert.equal(await layoutPosts(page), posts, "纯平移不提交布局");
    const samples = await page.evaluate(() => window.__lessplainDiagnostics!.events().filter(e => e.kind === "wheel"));
    assert.equal(samples.length, 180, "超过前32个事件的完整手势仍可取证");
    for (const sample of samples) {
      assert.equal(sample.route, 2);
      assert.ok(Number(sample.handledAt) >= Number(sample.receivedAt));
      assert.ok(Math.abs(Number(sample.afterX) - Number(sample.beforeX) + Number(sample.deltaX)) < 0.01);
      assert.ok(Math.abs(Number(sample.afterY) - Number(sample.beforeY) + Number(sample.deltaY)) < 0.01);
    }
    await page.evaluate(() => {
      const pane = document.querySelector(".react-flow__pane")!;
      for (let i = 0; i < 550; i++) pane.dispatchEvent(new WheelEvent("wheel", {
        deltaX: -1, deltaY: 1, shiftKey: true, bubbles: true, cancelable: true,
      }));
    });
    const recent = await page.evaluate(() => window.__lessplainDiagnostics!.events());
    assert.ok(recent.length <= 500);
    assert.ok(recent.filter(e => e.kind === "wheel").every(e => e.deltaX === -1 && e.deltaY === 1));
    assert.deepEqual(h.errors, []);
  } finally { await page.close(); }
}
