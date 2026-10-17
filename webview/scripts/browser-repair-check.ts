import assert from "node:assert/strict";
import type { Browser, Page } from "@playwright/test";
import { fixture, fit, harness, layoutPosts, readyCanvas, scale, viewport } from "./browser-harness";

const pathPoints = (page: Page, id: string) => page.locator(`.react-flow__edge[data-id='${id}'] .react-flow__edge-path`).evaluate((path: SVGPathElement) => {
  const numbers = path.getAttribute("d")!.match(/-?\d+(?:\.\d+)?/g)!.map(Number);
  return Array.from({ length: numbers.length / 2 }, (_, i) => ({ x: numbers[i * 2], y: numbers[i * 2 + 1] }));
});
/** 标题拖动、路径坐标和侧栏提示均通过真实鼠标事件验证。 */
export async function checkCanvasRepairs(browser: Browser) {
  const h = await harness(browser, { views: [fixture()], notes: [], browse: {} });
  const { page } = h; h.release(); await page.getByText("还没有答案", { exact: true }).waitFor(); await h.publish("view:fixture"); await readyCanvas(page); await fit(page);
  const settings = page.getByRole("button", { name: "画布行为", exact: true });
  await settings.hover();
  const tooltip = page.getByRole("tooltip", { name: "画布行为", exact: true }); await tooltip.waitFor();
  const tb = (await tooltip.boundingBox())!, sb = (await settings.boundingBox())!;
  assert.ok(tb.width > 60 && tb.height < 40 && tb.x + tb.width <= sb.x - 6, "右侧提示应横排并出现在按钮左侧");
  await settings.click(); await page.waitForTimeout(600); assert.equal(await page.getByRole("tooltip").count(), 0, "打开弹层和自动聚焦不能留下提示");
  await page.keyboard.press("Escape"); await fit(page);
  const cluster = page.locator('.canvas-cluster-node[data-cluster-id="cluster"]');
  // 选择当前命中分组框的空白点，避免用 force 点击穿过节点。
  const blank = await cluster.evaluate(el => {
    const r = el.getBoundingClientRect();
    for (let y = r.y + 55; y < Math.min(innerHeight - 10, r.bottom - 4); y += 10) for (let x = r.x + 5; x < Math.min(innerWidth - 10, r.right - 5); x += 10)
      if (document.elementFromPoint(x, y)?.classList.contains("react-flow__pane")) return { x, y };
    throw new Error("分组中没有可命中的空白测试区域");
  });
  const parent = cluster.locator(".."); const beforeBlank = await parent.getAttribute("style");
  await page.mouse.move(blank.x, blank.y); await page.mouse.down(); await page.mouse.move(blank.x + 15, blank.y + 10, { steps: 4 }); await page.mouse.up();
  assert.equal(await parent.getAttribute("style"), beforeBlank, "分组空白处左拖不得移动整个分组");
  const oldViewport = await viewport(page).getAttribute("style");
  await page.mouse.move(blank.x, blank.y); await page.mouse.down({ button: "right" });
  await page.mouse.move(blank.x + 30, blank.y + 25, { steps: 8 }); await page.mouse.up({ button: "right" });
  assert.notEqual(await viewport(page).getAttribute("style"), oldViewport, "分组内部空白右拖必须平移画布");
  assert.equal(await parent.getAttribute("style"), beforeBlank);
  assert.equal(await page.getByRole("menu").count(), 0, "右拖结束不应打开菜单");
  await page.mouse.click(blank.x + 30, blank.y + 25, { button: "right" });
  await page.getByRole("menuitem", { name: "显示全图", exact: true }).waitFor();
  assert.equal(await page.getByRole("menuitem", { name: "为节点添加注释" }).count(), 0);
  await page.keyboard.press("Escape");
  await page.waitForTimeout(200);
  const header = cluster.locator(".canvas-cluster-title"); const hb = (await header.boundingBox())!;
  const oldScale = scale(await viewport(page).getAttribute("style")); const oldBox = (await cluster.boundingBox())!;
  const inner = 'graph-edge/["graph","lookup"]';
  // 从实际边ID获取跨组边，避免测试绑死内部命名。
  const crossId = await page.locator('.react-flow__edge').evaluateAll(edges => edges.map(e => e.getAttribute("data-id")!).find(id => id.includes('cross-a'))!);
  const before = await pathPoints(page, inner), beforeCross = await pathPoints(page, crossId);
  const posts = await layoutPosts(page), writes = h.calls.filter(c => c.method === "browse").length;
  await page.mouse.move(hb.x + 35, hb.y + hb.height / 2); await page.mouse.down(); await page.mouse.move(hb.x + 65, hb.y + hb.height / 2 + 24, { steps: 10 });
  await page.waitForTimeout(25);
  assert.equal(h.calls.filter(c => c.method === "browse").length, writes, "拖动期间不保存位置");
  await page.mouse.up(); await page.waitForTimeout(250);
  const afterBox = (await cluster.boundingBox())!, dx = (afterBox.x - oldBox.x) / oldScale, dy = (afterBox.y - oldBox.y) / oldScale;
  assert.ok(Math.abs(dx) > 1 && Math.abs(dy) > 1, "标题拖动应该移动分组");
  const after = await pathPoints(page, inner);
  assert.equal(after.length, before.length);
  after.forEach((point, i) => assert.ok(Math.abs(point.x - before[i].x - dx) < 1 && Math.abs(point.y - before[i].y - dy) < 1, "分组内部折点应同步平移"));
  const afterCross = await pathPoints(page, crossId);
  assert.ok(Math.hypot(afterCross.at(-1)!.x - beforeCross.at(-1)!.x, afterCross.at(-1)!.y - beforeCross.at(-1)!.y) < 1, "跨组边另一端应保持不动");
  assert.equal(await layoutPosts(page), posts, "标题拖动与松手都不运行ELK");
  assert.deepEqual(h.errors, []); await page.close();
}
