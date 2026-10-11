import { readFile, writeFile } from "node:fs/promises";

// 同时生成可独立打开的预览，不依赖开发服务器。
let html = await readFile(
  new URL("../dist/index.html", import.meta.url),
  "utf8",
);
const script = html.match(/<script[^>]*src="([^"]+)"[^>]*><\/script>/);
const style = html.match(/<link[^>]*href="([^"]+\.css)"[^>]*>/);
if (!script || !style) throw new Error("Cannot find built assets");
const asset = (path) =>
  new URL(`../dist/${path.replace(/^\.\//, "")}`, import.meta.url);
const js = await readFile(asset(script[1]), "utf8");
const css = await readFile(asset(style[1]), "utf8");
html = html.replace(
  script[0],
  () =>
    `<script type="module">${js.replace(/<\/script/gi, "<\\/script")}</script>`,
);
html = html.replace(style[0], () => `<style>${css}</style>`);
await writeFile(new URL("../dist/preview.html", import.meta.url), html);
