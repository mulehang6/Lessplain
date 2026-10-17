import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import type { Browser } from "@playwright/test";
import { fixture, fit, harness, node, readyCanvas } from "./browser-harness";
import type { View } from "../src/runtime/types";

/** 简历讲解使用真实代码引用；示例状态只解释查询与计分，不伪装成运行结果。 */
export async function checkRedisResume(browser: Browser) {
  const document = JSON.parse(await readFile("../docs/examples/redis-resume-explainer.view.json", "utf8"));
  const view: View = { ...fixture(), document, sources: Object.fromEntries(Object.entries(document.sources).map(([id, source]) => [id, { ...(source as object), version: "fixture", line: 1 }])) };
  const h = await harness(browser, { views: [view], notes: [], browse: {} }), { page } = h;
  try {
    h.release(); await page.getByText("还没有答案", { exact: true }).waitFor(); await h.publish(view.ref);
    await readyCanvas(page); await fit(page);
    assert.equal(await page.locator(".react-flow").count(), 1);
    assert.equal(await page.locator(".canvas-cluster-node").count(), 2);
    assert.equal(await page.locator("iframe").count(), 0);
    await page.getByRole("tab", { name: "缓存未命中", exact: true }).click();
    await page.locator('[data-element-id="cache-miss-values"]').waitFor();
    await page.waitForTimeout(300);
    assert.equal(h.calls.findLast(c => c.method === "source")?.params.source, "hot-sql");
    await fit(page); await page.getByRole("tab", { name: "重复事件", exact: true }).click();
    await page.locator('[data-element-id="rank-duplicate-values"]').waitFor();
    await page.waitForTimeout(300);
    assert.equal(h.calls.findLast(c => c.method === "source")?.params.nodeId, "skip");
    await fit(page); await page.getByRole("tab", { name: "读取榜单", exact: true }).click();
    await page.locator('[data-element-id="rank-day-values"]').waitFor();
    await fit(page); await page.getByRole("combobox").click();
    await page.getByRole("option", { name: "月榜", exact: true }).click();
    await page.locator('[data-element-id="rank-month-values"]').waitFor();
    assert.equal(await page.locator('[data-element-id="rank-day-values"]').count(), 0);
    await fit(page); await node(page, "hot-graph", "cached").click();
    await page.locator('[data-element-id="cache-hit-values"]').waitFor();
    assert.equal(await page.getByRole("tab", { name: "缓存命中", exact: true }).getAttribute("aria-selected"), "true");
    assert.deepEqual(h.errors, []);
  } finally { await page.close(); }
}
