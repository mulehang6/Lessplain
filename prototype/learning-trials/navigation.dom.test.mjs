import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { JSDOM, VirtualConsole } from 'jsdom';

async function open(mode, options = {}) {
  const errors = [];
  const console = new VirtualConsole();
  console.on('jsdomError', error => errors.push(error));
  const html = await readFile(new URL(`./dist/${mode}.html`, import.meta.url), 'utf8');
  const dom = new JSDOM(html, {
    url: `https://trial.invalid/${mode}.html${options.hash ?? ''}`,
    runScripts: 'dangerously', virtualConsole: console,
    beforeParse(window) {
      window.HTMLElement.prototype.scrollIntoView = function () {};
      window.scrollTo = () => {};
      if (options.blockStorage) Object.defineProperty(window, 'localStorage', { get() { throw Error('blocked'); } });
      if (options.saved) window.localStorage.setItem('lessplain-learning-trial-navigation-v1', options.saved);
    },
  });
  assert.deepEqual(errors, []);
  return dom;
}

const click = (document, selector, text) => {
  const node = [...document.querySelectorAll(selector)].find(node => node.textContent.includes(text));
  assert.ok(node, `找不到 ${text}`);
  node.click();
};

test('目录与源码联动，跨模块返回后恢复滚动和选中行，刷新仍保留', async () => {
  const dom = await open('navigation');
  const document = dom.window.document;
  const active = () => document.querySelector('.tree-item[aria-current=location]');
  assert.ok(active().title.endsWith('/export/ConversationMarkdownExporter.kt'));
  const neighbors = () => [...document.querySelectorAll('.tree-item')].filter(row => /\/chat\/[^/]+$/.test(row.title)).map(row => row.title);
  const initialNeighbors = neighbors();
  assert.ok(initialNeighbors.some(path => path.endsWith('/state')));
  document.querySelector('.code-scroll').scrollTop = 320;
  document.querySelector('.directory-scroll').scrollTop = 55;
  document.querySelector('[data-line="31"]').click();
  click(document, '.tree-item', 'state');
  assert.deepEqual(neighbors(), initialNeighbors);
  assert.ok(!document.querySelector('.tree-item[title$="/export/ConversationMarkdownExporter.kt"]'));
  click(document, '.directory-list button', 'ChatRunController.kt');
  click(document, '.landmarks button', '工具循环');
  assert.ok(active().title.startsWith('shared/'));
  for (let i = 0; i < 3; i++) click(document, '.editor-toolbar button', '返回');
  assert.ok(active().title.endsWith('/export/ConversationMarkdownExporter.kt'));
  assert.equal(document.querySelector('.code-scroll').scrollTop, 320);
  assert.equal(document.querySelector('.directory-scroll').scrollTop, 55);
  assert.equal(document.querySelector('.code-line.selected').dataset.line, '31');
  const saved = dom.window.localStorage.getItem('lessplain-learning-trial-navigation-v1');
  const restored = await open('navigation', { saved });
  assert.equal(restored.window.document.querySelector('.code-scroll').scrollTop, 320);
  assert.equal(restored.window.document.querySelector('.code-line.selected').dataset.line, '31');
  dom.window.close(); restored.window.close();
});

test('阅读首屏只有第一遍，模拟输出随步骤前缀重建，切换后只显示目标遍', async () => {
  const dom = await open('reading');
  const document = dom.window.document;
  assert.equal(document.querySelectorAll('.reading-sheet').length, 1);
  assert.ok(document.querySelector('.reading-sheet').textContent.includes('TextDelta'));
  assert.ok(!document.querySelector('.reading-sheet').textContent.includes('nodeExecuteTools'));
  assert.equal(document.querySelector('.sim-output').hidden, true);
  click(document, '.sim-controls button', '下一步');
  click(document, '.sim-controls button', '下一步');
  assert.equal(document.querySelector('.sim-output').textContent, '你好！');
  click(document, '.sim-controls button', '上一步');
  assert.equal(document.querySelector('.sim-output').textContent, '你');
  click(document, '.lesson-end button', '继续下一遍');
  assert.ok(document.querySelector('.reading-sheet').textContent.includes('nodeExecuteTools'));
  assert.ok(!document.querySelector('.reading-sheet').textContent.includes('TextDelta'));
  assert.ok(document.querySelector('.source-link').href.includes('navigation.html#shared'));
  assert.ok(document.querySelector('.explain summary').textContent.includes('整段没懂'));
  dom.window.close();
});

test('没有本地存储仍可导航；源码入口可定位文本，损坏片段安全忽略', async () => {
  const path = 'desktopApp/src/main/kotlin/com/agent/app/chat/state/ChatRunController.kt';
  const dom = await open('navigation', { blockStorage: true, hash: `#${encodeURIComponent(path)}|${encodeURIComponent('runningJob?.cancel()')}` });
  assert.ok(dom.window.document.querySelector('.code-line.selected').textContent.includes('runningJob?.cancel()'));
  assert.ok(dom.window.document.querySelector('#save-status').textContent.includes('无法保存'));
  click(dom.window.document, '.landmarks button', '聊天导出');
  assert.ok(dom.window.document.querySelector('.file-heading h1').textContent.includes('ConversationMarkdownExporter'));
  const malformed = await open('navigation', { hash: '#%invalid' });
  assert.ok(malformed.window.document.querySelector('.file-heading h1').textContent.includes('ConversationMarkdownExporter'));
  dom.window.close(); malformed.window.close();
});
