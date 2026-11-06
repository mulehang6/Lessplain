const app = document.querySelector('#app');
const mode = DATA.mode;
const saveStatus = document.querySelector('#save-status');
const storageKey = `lessplain-learning-trial-${mode}-v1`;
let storage;
try { storage = window.localStorage; } catch { storage = null; }
document.querySelector(`#${mode}-tab`).setAttribute('aria-current', 'page');
const el = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
};
const button = (label, action, className) => {
  const node = el('button', className, label);
  node.type = 'button';
  node.addEventListener('click', action);
  return node;
};
const save = value => {
  saveStatus.textContent = writeSaved(storage, storageKey, value) ? '阅读位置已保存在此浏览器' : '本次可浏览，当前浏览器无法保存位置';
};
document.querySelector('#reset').addEventListener('click', () => {
  try { storage?.removeItem(storageKey); } catch { /* 无存储仍可重新开始。 */ }
  if (mode === 'reading') { lessonIndex = 0; renderReading(); }
  else { navState = createNavigation(DATA.snapshot); renderNavigation(); save(navState); }
});

let lessonIndex = Math.max(0, Math.min(2, Number(readSaved(storage, storageKey)?.lessonIndex) || 0));
let navState = createNavigation(DATA.snapshot, readSaved(storage, storageKey));
let treeScroll;
let codeScroll;
let selectedLine = null;

function sourceHref(key) {
  const source = DATA.sources[key];
  return `navigation.html#${encodeURIComponent(source.path)}|${encodeURIComponent(source.context)}`;
}

function makeDiagram(diagram) {
  const box = el('div', 'diagram');
  box.setAttribute('role', 'img');
  box.setAttribute('aria-label', diagram.graph.nodes.map(node => node.label).join('，然后'));
  const row = el('div', 'diagram-row');
  diagram.graph.nodes.forEach((node, index) => {
    if (index) row.append(el('span', 'diagram-arrow', '→'));
    row.append(el('div', 'diagram-node', node.label));
  });
  box.append(row);
  const loop = diagram.graph.edges.find(edge => edge.condition);
  if (loop) box.append(el('p', 'loop-note', `↶ ${loop.label}，回到“应用执行读取”`));
  return box;
}

function makeSimulation(simulation) {
  const section = el('section', 'simulation');
  section.setAttribute('aria-label', '预设教学情境');
  section.append(el('div', 'simulation-label', '预设教学情境 · 按下一步观察，不执行模型或工具'));
  const code = el('pre');
  const lines = simulation.code.split('\n').map(line => el('span', 'sim-line', line));
  code.append(...lines);
  const caption = el('div', 'sim-caption');
  caption.setAttribute('aria-live', 'polite');
  const output = el('div', 'sim-output');
  const controls = el('div', 'sim-controls');
  const counter = el('span');
  const steps = simulation.scenarios[0].steps;
  let index = 0;
  const previous = button('上一步', () => { index--; update(); });
  const next = button('下一步', () => { index++; update(); });
  const replay = button('重看', () => { index = 0; update(); });
  controls.append(counter, replay, previous, next);
  function update() {
    lines.forEach((line, offset) => line.classList.toggle('active', steps[index].highlightLines.includes(offset + 1)));
    caption.textContent = steps[index].caption;
    output.textContent = steps.slice(0, index + 1).map(step => step.stdout ?? '').join('');
    output.hidden = !output.textContent;
    counter.textContent = `${index + 1} / ${steps.length}`;
    previous.disabled = index === 0;
    next.disabled = index === steps.length - 1;
  }
  update();
  section.append(code, caption, output, controls);
  return section;
}

function renderReading() {
  app.replaceChildren();
  app.className = 'reading-layout';
  const rail = el('aside', 'lesson-rail');
  rail.setAttribute('aria-label', '三遍阅读');
  rail.append(el('p', 'muted', '先走完一小段，再决定是否继续。'));
  const names = ['一句话变成回复', '加入一次工具调用', '认清几个停点'];
  DATA.lessons.forEach((lesson, index) => {
    const choice = button(`${index + 1} / 3`, () => { lessonIndex = index; renderReading(); window.scrollTo(0, 0); }, 'lesson-choice');
    choice.append(el('strong', '', names[index]));
    if (index === lessonIndex) choice.setAttribute('aria-current', 'step');
    rail.append(choice);
  });
  const sheet = el('article', 'reading-sheet');
  const lesson = DATA.lessons[lessonIndex];
  sheet.append(el('div', 'eyebrow', `第 ${lessonIndex + 1} 遍 / 共 3 遍`), el('h1', '', names[lessonIndex]));
  lesson.blocks.forEach((block, index) => {
    if (block.text) sheet.append(el('p', index === 0 ? 'intro' : '', block.text));
    else if (block.diagram) sheet.append(makeDiagram(lesson.diagrams.find(item => item.id === block.diagram)));
    else if (block.simulation) sheet.append(makeSimulation(lesson.simulations.find(item => item.id === block.simulation)));
    else {
      const link = el('a', 'source-link', `${block.label} ↗`);
      link.href = sourceHref(block.source);
      sheet.append(link);
    }
  });
  const explain = el('details', 'explain');
  explain.append(el('summary', '', '这一遍整段没懂'));
  const hints = [
    '先把类名放下。只想象屏幕先显示“你”，再收到“好！”。文字接在一起，就成了“你好！”。带着这个变化重看模拟即可。',
    '把模型想成正在等资料的人。它先说“帮我读一下”，应用去读，然后把读到的内容交给它。它拿到资料以后，才能继续回答。',
    '先只比较两种状态：等你批准是暂停等输入，点停止是你取消这轮。它们都不同于正常完成。',
  ];
  explain.append(el('p', '', hints[lessonIndex]));
  explain.append(el('p', '', '需要 Agent 换一种讲法时，可在 Lessplain 教程里选中整段，写“这段没懂，请换个例子”，再按现有流程交给 Agent。这里不会自动发起请求。'));
  sheet.append(explain);
  const end = el('div', 'lesson-end');
  end.append(el('p', '', '可以停在这里，不要求一次看完。'));
  if (lessonIndex < 2) end.append(button('继续下一遍 →', () => { lessonIndex++; renderReading(); window.scrollTo(0, 0); }, 'primary'));
  else { const link = el('a', '', '单独试目录位置 →'); link.href = 'navigation.html'; end.append(link); }
  sheet.append(end);
  app.append(rail, sheet);
  save({ lessonIndex });
}

function capturePosition() {
  if (!treeScroll || !codeScroll) return;
  navState = remember(navState, { treeTop: treeScroll.scrollTop, treeLeft: treeScroll.scrollLeft, codeTop: codeScroll.scrollTop, codeLeft: codeScroll.scrollLeft, selectedLine });
  save(navState);
}

function go(path, offset) {
  capturePosition();
  navState = offset === undefined ? navigate(navState, path, DATA.snapshot.entries) : travel(navState, offset);
  renderNavigation();
  save(navState);
}

function treeRow(path, depth, label) {
  const entry = DATA.snapshot.entries[path];
  const current = currentPath(navState);
  const expanded = entry.kind === 'directory' && !entry.omitted && isExpanded(path, current);
  const row = button('', () => go(path), `tree-item${entry.omitted ? ' omitted' : ''}`);
  row.style.paddingLeft = `${12 + depth * 15}px`;
  row.title = `${path}${entry.omitted ? ' · 此区域未收录源码' : ''}`;
  row.append(el('span', 'glyph', entry.kind === 'file' ? '·' : expanded ? '▾' : '▸'), el('span', 'name', label ?? pathName(path)));
  if (path === current) { row.setAttribute('aria-current', 'location'); row.append(el('span', 'here', '当前')); }
  treeScroll.append(row);
  if (!expanded) return;
  // 合并中间的命名空间，只保留完整路径标签和真实同级目录。
  const bases = { desktopApp: 'desktopApp/src/main/kotlin/com/agent/app', shared: 'shared/src/jvmMain/kotlin/com/agent/shared/agent' };
  if (bases[path] && isExpanded(bases[path], current)) treeRow(bases[path], depth + 1, bases[path].slice(path.length + 1));
  else for (const child of entry.children ?? []) treeRow(child, depth + 1);
}

function renderNavigation() {
  app.replaceChildren();
  app.className = 'navigation-layout';
  const path = currentPath(navState);
  const entry = DATA.snapshot.entries[path];
  const position = navState.positions[path] ?? {};
  selectedLine = Number.isInteger(position.selectedLine) ? position.selectedLine : null;
  const panel = el('aside', 'directory-panel');
  panel.setAttribute('aria-label', '聚焦目录');
  const head = el('div', 'directory-header');
  head.append(el('div', 'project-label', DATA.snapshot.project), el('p', 'muted', '当前分支展开，邻居留在原位。中间路径合并显示。'));
  treeScroll = el('div', 'directory-scroll');
  treeScroll.setAttribute('tabindex', '0');
  for (const child of DATA.snapshot.entries[''].children) treeRow(child, 0);
  panel.append(head, treeScroll);
  const editor = el('section', 'editor-panel');
  editor.setAttribute('aria-label', '只读源码快照');
  const toolbar = el('div', 'editor-toolbar');
  const back = button('← 返回', () => go(null, -1));
  back.disabled = navState.index === 0;
  const forward = button('前进 →', () => go(null, 1));
  forward.disabled = navState.index === navState.history.length - 1;
  const landmarks = el('div', 'landmarks');
  landmarks.setAttribute('aria-label', '试读位置');
  for (const mark of DATA.snapshot.landmarks) landmarks.append(button(mark.label, () => go(mark.path)));
  toolbar.append(back, forward, landmarks);
  const crumbs = el('nav', 'breadcrumb');
  crumbs.setAttribute('aria-label', '当前路径');
  crumbs.append(button(DATA.snapshot.project, () => go('')));
  path.split('/').filter(Boolean).forEach((name, index, parts) => {
    crumbs.append(el('span', '', '/'), button(name, () => go(parts.slice(0, index + 1).join('/'))));
  });
  const heading = el('div', 'file-heading');
  heading.append(el('h1', '', pathName(path) || DATA.snapshot.project), el('p', '', entry.kind === 'file' ? '只读源码快照 · 点击行可留下当前阅读位置' : '按真实目录与文件排序'));
  codeScroll = el('div', 'code-scroll');
  codeScroll.setAttribute('tabindex', '0');
  if (entry.code !== undefined) {
    const code = el('pre', 'source-code');
    entry.code.split('\n').forEach((line, index) => {
      const row = el('span', `code-line${selectedLine === index + 1 ? ' selected' : ''}`);
      row.dataset.line = index + 1;
      row.append(el('span', 'line-number', String(index + 1)), document.createTextNode(line));
      code.append(row);
    });
    code.addEventListener('click', event => {
      const row = event.target.closest('.code-line');
      if (!row) return;
      code.querySelector('.selected')?.classList.remove('selected');
      row.classList.add('selected'); selectedLine = Number(row.dataset.line); capturePosition();
    });
    codeScroll.append(code);
  } else if (entry.omitted) {
    codeScroll.append(el('div', 'notice', '这里保留真实目录位置，本次未收录这一区域的源码。可以通过上方的试读位置查看聊天导出、状态处理和工具循环。'));
  } else {
    const list = el('div', 'directory-list');
    for (const child of entry.children) list.append(button(pathName(child), () => go(child)));
    codeScroll.append(list);
  }
  const help = el('div', 'editor-help', '试一下：从导出切到 state，再切到 shared。最后不用返回按钮，找回导出。也可以用返回，检查阅读位置是否还在。');
  editor.append(toolbar, crumbs, heading, codeScroll, help);
  app.append(panel, editor);
  treeScroll.scrollTop = Number(position.treeTop) || 0;
  treeScroll.scrollLeft = Number(position.treeLeft) || 0;
  codeScroll.scrollTop = Number(position.codeTop) || 0;
  codeScroll.scrollLeft = Number(position.codeLeft) || 0;
  // 首次进入新文件时让当前位置进入可见范围；返回使用已保存的视口。
  if (!navState.positions[path]) treeScroll.querySelector('[aria-current=location]')?.scrollIntoView({ block: 'nearest' });
}

if (mode === 'reading') renderReading();
else {
  const [encodedPath, encodedFocus] = location.hash.slice(1).split('|');
  let linkedPath, focus;
  try { linkedPath = decodeURIComponent(encodedPath ?? ''); focus = decodeURIComponent(encodedFocus ?? ''); } catch { /* 无效片段不影响默认导航。 */ }
  if (linkedPath && Object.hasOwn(DATA.snapshot.entries, linkedPath)) navState = navigate(navState, linkedPath, DATA.snapshot.entries);
  renderNavigation();
  if (focus) {
    const code = DATA.snapshot.entries[currentPath(navState)].code ?? '';
    const line = code.split('\n').findIndex(text => text.includes(focus)) + 1;
    if (line > 0) {
      selectedLine = line;
      const row = codeScroll.querySelector(`[data-line="${line}"]`);
      row?.classList.add('selected'); row?.scrollIntoView({ block: 'center' });
    }
  }
  capturePosition();
}
window.addEventListener('pagehide', () => { if (mode === 'navigation') capturePosition(); });
