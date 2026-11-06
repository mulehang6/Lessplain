import { readdir, readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { lessons, sources } from './lessons.mjs';

const project = new URL('../../../mulehang-agent/', import.meta.url);
const output = new URL('./dist/', import.meta.url);
const entries = {};
const branches = [
  'desktopApp/src/main/kotlin/com/agent/app/chat',
  'shared/src/jvmMain/kotlin/com/agent/shared/agent/koog',
];

/** 只采集两片代码区域；其他同级目录仍保留名字，并标明未收录。 */
async function collect(path = '') {
  const children = (await readdir(new URL(path ? `${path}/` : './', project), { withFileTypes: true }))
    .filter(item => !item.name.startsWith('.') && !['build', 'node_modules'].includes(item.name))
    .filter(item => item.isDirectory() || item.name.endsWith('.kt'))
    .sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  entries[path] = { kind: 'directory', children: [] };
  for (const item of children) {
    const child = path ? `${path}/${item.name}` : item.name;
    entries[path].children.push(child);
    const included = branches.some(branch => branch === child || branch.startsWith(`${child}/`) || child.startsWith(`${branch}/`));
    if (!included) entries[child] = { kind: item.isDirectory() ? 'directory' : 'file', omitted: true };
    else if (item.isDirectory()) await collect(child);
    else entries[child] = { kind: 'file', code: await readFile(new URL(child, project), 'utf8') };
  }
}

await collect();
const snapshot = {
  project: 'mulehang-agent', capturedAt: new Date().toISOString(), entries,
  initial: 'desktopApp/src/main/kotlin/com/agent/app/chat/export/ConversationMarkdownExporter.kt',
  landmarks: [
    { label: '聊天导出', path: 'desktopApp/src/main/kotlin/com/agent/app/chat/export/ConversationMarkdownExporter.kt' },
    { label: '发送与停止', path: sources.send.path },
    { label: '接收文字', path: sources.delta.path },
    { label: '工具循环', path: sources.tools.path },
  ],
};
const template = await readFile(new URL('./page.html', import.meta.url), 'utf8');
const css = await readFile(new URL('./styles.css', import.meta.url), 'utf8');
const model = (await readFile(new URL('./navigation.mjs', import.meta.url), 'utf8')).replaceAll(/^export /gm, '');
const client = await readFile(new URL('./client.js', import.meta.url), 'utf8');
await mkdir(output, { recursive: true });
for (const mode of ['reading', 'navigation']) {
  const data = JSON.stringify({ mode, snapshot, lessons, sources }).replaceAll('<', '\\u003c');
  const html = template.replace('/* STYLES */', () => css).replace('/* DATA */', () => `const DATA = ${data};`).replace('/* MODEL */', () => model).replace('/* CLIENT */', () => client);
  await writeFile(new URL(`${mode}.html`, output), html);
}
await writeFile(new URL('lessons.json', output), JSON.stringify({ sources, lessons }, null, 2));
console.log(`已生成 ${fileURLToPath(output)}，收录 ${Object.values(entries).filter(entry => entry.code).length} 份只读源码。`);
