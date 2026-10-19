import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import type { Browser } from "@playwright/test";
import { fixture, harness } from "./browser-harness";
import type { View } from "../src/runtime/types";

/** 空闲画布不能因控制器发布形成自触发循环，持续分配至 renderer OOM。 */
export async function checkCanvasIdle(browser: Browser) {
  const document = JSON.parse(await readFile("../docs/examples/tag-concepts.view.json", "utf8"));
  for (const diagnostics of [false, true]) {
    const view: View = { ...fixture(), document, sources: Object.fromEntries(Object.entries(document.sources).map(([id, source]) =>
      [id, { ...(source as object), version: "fixture", line: 1 }])) };
    const h = await harness(browser, { views: [view], notes: [], browse: {} }, false, diagnostics);
    try {
      h.release(); await h.page.getByText("还没有答案", { exact: true }).waitFor(); await h.publish(view.ref);
      await h.page.locator(".canvas-companion-node").first().waitFor();
      await h.page.waitForTimeout(500);
      const cdp = await h.page.context().newCDPSession(h.page);
      await cdp.send("Performance.enable");
      const sample = async () => {
        await cdp.send("HeapProfiler.collectGarbage");
        const heap = await cdp.send("Runtime.getHeapUsage"), dom = await cdp.send("Memory.getDOMCounters");
        const metrics = await cdp.send("Performance.getMetrics");
        return { heap: heap.usedSize, listeners: dom.jsEventListeners,
          script: metrics.metrics.find(item => item.name === "ScriptDuration")!.value };
      };
      const before = await sample();
      await h.page.waitForTimeout(2500);
      const after = await sample();
      assert.ok(after.heap - before.heap < 8 * 1024 * 1024, `空闲堆持续增长：${JSON.stringify({ diagnostics, before, after })}`);
      assert.ok(after.script - before.script < 0.5, `空闲脚本持续忙碌：${JSON.stringify({ diagnostics, before, after })}`);
      assert.ok(after.listeners - before.listeners < 100, "空闲事件监听器不得持续累积");
      assert.deepEqual(h.errors, []);
    } finally { await h.page.close(); }
  }
}
