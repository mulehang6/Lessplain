import { readFile } from "node:fs/promises";
import path from "node:path";
import type { Browser, Page } from "@playwright/test";
import assert from "node:assert/strict";
import type { Note, Snapshot, View } from "../src/runtime/types";

export const source = { path: "Example.java", text: "return cache.get(key);", version: "fixture", line: 1 };

/** Deliberately small facts, with two real cross-cluster alternatives and duplicate local node IDs. */
export function fixture(): View {
  return {
    ref: "view:fixture", revision: 1, createdAt: 1, updatedAt: 1, origin: { state: {} }, sources: { cache: source },
    document: { title: "测试理解", question: "历史问题不常驻画布", demos: {
      sample: { html: `<button id="select">选择缓存</button><button id="invalid">无效选择</button><script>
        for(const [id,value] of [["select","cache"],["invalid","missing"]]) document.getElementById(id).onclick=()=>parent.postMessage({channel:"lessplain-demo",method:"setState",params:{path:"/selection",value}},"*");
      </script>` },
    }, spec: { root: "board", state: { selection: "request", hit: false }, elements: {
      board: { type: "Board", props: { edges: [
        { id: "cross-a", source: { elementId: "graph", nodeId: "cache" }, target: { elementId: "other", nodeId: "return" }, kind: "data_flow", label: "分支甲" },
        { id: "cross-b", source: { elementId: "graph", nodeId: "cache" }, target: { elementId: "third", nodeId: "return" }, kind: "data_flow", label: "分支乙" },
      ] }, children: ["cluster", "cluster2", "cluster3"] },
      cluster: { type: "Cluster", props: { title: "缓存职责" }, slots: { diagram: ["graph"], companion: ["stack"] } },
      cluster2: { type: "Cluster", props: { title: "返回职责甲" }, slots: { diagram: ["other"] } },
      cluster3: { type: "Cluster", props: { title: "返回职责乙" }, slots: { diagram: ["third"] } },
      graph: { type: "Graph", props: { direction: "RIGHT", selectionPath: "/selection", nodes: [
        { id: "request", label: "读取请求", source: "cache" },
        { id: "cache", label: "读取缓存", detail: "默认不显示这段长解释", source: "cache" },
      ], edges: [{ id: "lookup", source: "request", target: "cache", kind: "call", label: "读取" }] } },
      other: { type: "Graph", props: { selectionPath: "/other", nodes: [{ id: "return", label: "返回甲", source: "cache" }], edges: [] } },
      third: { type: "Graph", props: { selectionPath: "/third", nodes: [{ id: "return", label: "返回乙" }], edges: [] } },
      stack: { type: "Stack", props: { direction: "vertical", gap: "md" }, children: ["json", "demo"] },
      json: { type: "JsonView", props: { value: { $state: "/" }, label: "共享状态" } },
      demo: { type: "Demo", props: { demo: "sample", title: "隔离交互", height: 120 } },
    } } },
  };
}

export type BridgeCall = { method: string; params: Record<string, any> };

/** Headless, file interception only: no HTTP server, IDE, screenshots or visible browser. */
export async function harness(browser: Browser, snapshot: Snapshot, editor = false) {
  const page = await browser.newPage({ viewport: { width: 1080, height: 780 } });
  page.setDefaultTimeout(10000);
  const calls: BridgeCall[] = [], errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  let sequence = 1;
  let failNextNote = false;
  let failNextCopy = false;
  let failNextEdit = false, failNextDelete = false, noteSequence = snapshot.notes.length;
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const publish = (select?: string) => page.evaluate(message => window.__lessplainReceive?.(message), { snapshot, sequence: ++sequence, select });
  await page.route("https://lessplain.local/**", async route => {
    const file = path.join(process.cwd(), "dist", new URL(route.request().url()).pathname);
    let body = await readFile(file);
    if (file.endsWith("index.html")) body = Buffer.from(body.toString().replace("<head>", `<head><script>
      window.__lessplainBoot=${JSON.stringify({ title: "Lessplain", hostId: editor ? "editor-id" : "tool-id", host: editor ? "editor" : "toolwindow", ...(editor ? { ref: "view:fixture" } : {}) })};
      Object.entries({"--background":"#1e1f22","--card":"#2b2d30","--foreground":"#cccccc","--ide-color-scheme":"dark","--diagram-purple":"#967bef","--diagram-warning":"#d6ae58","--diagram-success":"#5fad65","--diagram-failure":"#db5860"}).forEach(([name,value])=>document.documentElement.style.setProperty(name,value));
    </script>`));
    await route.fulfill({ body, contentType: file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css" : "text/html", headers: {
      "Content-Security-Policy": "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; worker-src 'self'; frame-src 'self' about:; connect-src 'none';",
    } });
  });
  await page.exposeFunction("__testCall", async (raw: string) => {
    const request = JSON.parse(raw) as BridgeCall;
    calls.push(request);
    if (request.method === "copy" && failNextCopy) { failNextCopy = false; throw new Error("测试复制失败"); }
    if (request.method === "ready") { await gate; return { snapshot: editor ? snapshot : { views: [], notes: [], browse: {} }, sequence, ref: editor ? "view:fixture" : undefined, theme: {} }; }
    if (request.method === "browse") snapshot.browse[request.params.ref] = request.params.state;
    if (request.method === "rename") {
      const view = snapshot.views.find(view => view.ref === request.params.ref)!;
      view.document = { ...view.document, title: request.params.title }; view.revision++; await publish();
    }
    if (request.method === "delete") {
      const removed = new Set<string>([request.params.ref]);
      let changed = true;
      while (changed) {
        changed = false;
        for (const view of snapshot.views) if (view.parentRef && removed.has(view.parentRef) && !removed.has(view.ref)) { removed.add(view.ref); changed = true; }
      }
      snapshot.views = snapshot.views.filter(view => !removed.has(view.ref));
      snapshot.notes = snapshot.notes.filter(note => !note.ownerRef || !removed.has(note.ownerRef));
      await publish();
    }
    if (request.method === "note") {
      if (failNextNote) { failNextNote = false; throw new Error("测试保存失败"); }
      snapshot.notes.push({ ...request.params, ref: "note:fixture-" + (++noteSequence) + "@1", ownerRef: request.params.origin.viewRef, status: "pending", createdAt: noteSequence } as Note);
      await publish();
    }
    if (request.method === "deleteNote") {
      if (failNextDelete) { failNextDelete = false; throw new Error("测试删除失败"); }
      snapshot.notes = snapshot.notes.filter(note => note.ref !== request.params.ref); await publish();
    }
    if (request.method === "editNote") {
      if (failNextEdit) { failNextEdit = false; throw new Error("测试编辑失败"); }
      snapshot.notes = snapshot.notes.map(note => note.ref === request.params.ref ? { ...note, text: request.params.text, ref: note.ref.split("@")[0] + "@" + (Number(note.ref.split("@")[1]) + 1) } : note);
      await publish();
    }
    return null;
  });
  await page.addInitScript(() => {
    const post = Worker.prototype.postMessage;
    (window as unknown as { __layoutPosts: number }).__layoutPosts = 0;
    Worker.prototype.postMessage = function (...args: Parameters<Worker["postMessage"]>) {
      (window as unknown as { __layoutPosts: number }).__layoutPosts++;
      if ((window as unknown as { __holdLayouts?: boolean }).__holdLayouts) return;
      return post.apply(this, args);
    };
    window.__lessplainQuery = (raw: string) => {
      const request = JSON.parse(raw);
      (window as unknown as { __testCall(raw: string): Promise<unknown> }).__testCall(raw).then(result =>
        window.__lessplainReceive?.({ id: request.id, result })).catch(error => window.__lessplainReceive?.({ id: request.id, error: error.message }));
    };
  });
  await page.goto("https://lessplain.local/index.html");
  return { page, calls, errors, publish, release, failCopy: () => { failNextCopy = true; }, failNote: () => { failNextNote = true; }, failEdit: () => { failNextEdit = true; }, failDelete: () => { failNextDelete = true; } };
}

export const node = (page: Page, graph: string, id: string) => page.locator(`.lesson-node[data-element-id="${graph}"][data-node-id="${id}"]`);
export const viewport = (page: Page) => page.locator(".react-flow__viewport");
export const scale = (style: string | null) => Number(/scale\(([^)]+)\)/.exec(style ?? "")?.[1]);
export const layoutPosts = (page: Page) => page.evaluate(() => (window as unknown as { __layoutPosts: number }).__layoutPosts);
export async function fit(page: Page) {
  await page.getByRole("button", { name: "显示全图", exact: true }).click();
  await page.waitForTimeout(300);
}
export async function readyCanvas(page: Page) {
  await page.waitForFunction(() => document.querySelectorAll(".react-flow__edge-path").length >= 3);
  await page.waitForTimeout(350);
}

/** Playwright 对变换后的 srcdoc frame 不缩放内部 locator 坐标；显式换算真实鼠标点并检查命中。 */
export async function clickFrameButton(page: Page, title: string, label: string) {
  const frame = page.locator(`iframe[title="${title}"]`);
  const button = page.frameLocator(`iframe[title="${title}"]`).getByRole("button", { name: label, exact: true });
  await button.waitFor({ state: "visible" });
  const geometry = await frame.evaluate((element: HTMLIFrameElement) => ({ rect: element.getBoundingClientRect().toJSON(), width: element.offsetWidth, height: element.offsetHeight, left: element.clientLeft, top: element.clientTop }));
  const local = await button.evaluate(element => {
    const rect = element.getBoundingClientRect(), x = rect.x + rect.width / 2, y = rect.y + rect.height / 2;
    return { x, y, hit: element.contains(document.elementFromPoint(x, y)) };
  });
  assert.ok(local.hit, "iframe内按钮中心不可命中");
  const point = { x: geometry.rect.x + (geometry.left + local.x) * geometry.rect.width / geometry.width,
    y: geometry.rect.y + (geometry.top + local.y) * geometry.rect.height / geometry.height };
  assert.equal(await page.evaluate(({ point, title }) => document.elementFromPoint(point.x, point.y)?.getAttribute("title") === title, { point, title }), true, "实际屏幕点应命中该iframe，不能强制点穿遮挡");
  await page.mouse.click(point.x, point.y);
}
