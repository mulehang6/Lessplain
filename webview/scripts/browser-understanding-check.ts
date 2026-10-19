import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { expect, type Browser } from "@playwright/test";
import { clickFrameButton, fixture, fit, harness, layoutPosts, node, viewport } from "./browser-harness";
import type { View } from "../src/runtime/types";

async function example(name: string): Promise<View> {
  const document = JSON.parse(await readFile(`../docs/examples/${name}.view.json`, "utf8"));
  return { ...fixture(), document, sources: Object.fromEntries(Object.entries(document.sources)
    .map(([id, source]) => [id, { ...(source as object), version: "fixture", line: 1 }])) };
}

/** 比较职责直接定位声明，无关系的三个概念保持同时可见。 */
export async function checkSourceNodes(browser: Browser) {
  const view = await example("tag-source-nodes"), h = await harness(browser, { views: [view], notes: [], browse: {} });
  const { page } = h;
  try {
    h.release(); await page.getByText("还没有答案", { exact: true }).waitFor(); await h.publish(view.ref);
    await page.locator(".lesson-node").last().waitFor(); await page.waitForTimeout(250); await fit(page);
    assert.equal(await page.locator(".lesson-node").count(), 3);
    assert.equal(await page.locator(".react-flow__edge,iframe,[role=tab],[role=combobox]").count(), 0);
    assert.equal(await page.locator(".canvas-companion-node").count(), 0);
    for (const [id, source] of [["article", "article-record"], ["config", "config-storage"], ["navigation", "ui-select"]]) {
      await node(page, "tag-sources", id).click();
      await expect.poll(() => h.calls.findLast(c => c.method === "source")?.params.source).toBe(source);
      await page.waitForTimeout(260);
    }
    assert.deepEqual(h.errors, []);
  } finally { await page.close(); }
}

/** 用户操作验证缓存时效及旧返回值；到期不发起查询，缓存命中不续写TTL。 */
export async function checkCacheUnderstanding(browser: Browser) {
  const view = await example("home-sidebar-cache"), h = await harness(browser, { views: [view], notes: [], browse: {} });
  const { page } = h, title = "缓存与返回结果";
  const frame = page.frameLocator(`iframe[title="${title}"]`);
  const values = (id: string) => frame.locator(`#${id} li`).allTextContents();
  const click = async (label: string) => {
    try { await clickFrameButton(page, title, label); }
    catch (error) {
      const geometry = await frame.locator("body").evaluate(el => ({ height: el.scrollHeight, width: el.clientWidth, viewport: innerHeight }));
      throw new Error(`${label}: ${JSON.stringify(geometry)}`, { cause: error });
    }
  };
  try {
    h.release(); await page.getByText("还没有答案", { exact: true }).waitFor(); await h.publish(view.ref);
    await frame.getByRole("button", { name: "访问侧栏", exact: true }).waitFor();
    await page.waitForTimeout(300); await fit(page);
    const posts = await layoutPosts(page), camera = await viewport(page).getAttribute("style");
    const frameHandle = await page.locator("iframe").elementHandle();
    await click("访问侧栏"); await expect(frame.locator("#queries")).toHaveText("1");
    const old = await values("returned"); assert.deepEqual(old, ["文章 A", "文章 B", "文章 C"]);
    await click("修改模拟数据");
    assert.deepEqual(await values("cache"), old); assert.deepEqual(await values("returned"), old);
    await click("推进 1 分钟"); await click("访问侧栏");
    await expect(frame.locator("#origin")).toContainText("缓存命中");
    await expect(frame.locator("#queries")).toHaveText("1");
    await expect(frame.locator("#ttl")).toHaveText("剩余 4:00");
    assert.deepEqual(await values("returned"), old);
    for (let i = 0; i < 4; i++) await click("推进 1 分钟");
    await expect(frame.locator("#ttl")).toHaveText("已过期，等待下次访问");
    await expect(frame.locator("#queries")).toHaveText("1");
    assert.deepEqual(await values("returned"), old, "到期不会改写上次返回，也不自动查库");
    await click("访问侧栏"); await expect(frame.locator("#queries")).toHaveText("2");
    const fresh = ["文章 C", "文章 A", "文章 B"];
    assert.deepEqual(await values("returned"), fresh); assert.deepEqual(await values("cache"), fresh);
    await expect(frame.locator("#ttl")).toHaveText("剩余 5:00");
    await expect(frame.locator(".scope")).toContainText("非运行记录");
    for (const [label, source] of [["查看查询与排序 ↗", "hot-sql"], ["查看写入后 5 分钟过期 ↗", "local-ttl"],
      ["查看命中时跳过方法体 ↗", "home-cache"], ["首页侧栏调用 ↗", "home-entry"], ["组装热门文章 ↗", "hot-limit"]]) {
      await click(label);
      await expect.poll(() => h.calls.findLast(c => c.method === "source")?.params.source).toBe(source);
    }
    for (const scheme of ["light", "dark"]) {
      await page.evaluate(scheme => {
        const style = document.documentElement.style;
        style.setProperty("--ide-color-scheme", scheme);
        style.setProperty("--card", scheme === "light" ? "#ffffff" : "#2b2d30");
        style.setProperty("--foreground", scheme === "light" ? "#252525" : "#cccccc");
      }, scheme);
      await expect.poll(() => frame.locator("html").evaluate(el => getComputedStyle(el).colorScheme)).toBe(scheme);
      await expect.poll(() => frame.locator("body").evaluate(el => getComputedStyle(el).backgroundColor))
        .toBe(scheme === "light" ? "rgb(255, 255, 255)" : "rgb(43, 45, 48)");
      assert.deepEqual(await values("returned"), fresh, "主题切换不重置推演");
    }
    assert.equal(await page.locator("iframe").evaluate((el, old) => el === old, frameHandle), true);
    assert.equal(await viewport(page).getAttribute("style"), camera);
    assert.equal(await layoutPosts(page), posts, "操作不增加画布测量或布局");
    await click("重置"); await expect(frame.locator("#queries")).toHaveText("0");
    await expect(frame.locator("#clock")).toHaveText("0:00");
    assert.deepEqual(await values("cache"), ["尚无结果"]); assert.deepEqual(await values("returned"), ["尚无返回"]);
    assert.deepEqual(h.errors, []);
  } finally { await page.close(); }
}
