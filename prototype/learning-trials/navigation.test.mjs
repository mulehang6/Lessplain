import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNavigation, currentPath, isExpanded, remember, navigate, travel, readSaved, writeSaved } from './navigation.mjs';
import { lessons, sources, tutorialPayload } from './lessons.mjs';

const entries = Object.fromEntries(['chat/export/a.kt', 'chat/state/b.kt', 'shared/koog/c.kt'].map(path => [path, {}]));
const snapshot = { entries, initial: 'chat/export/a.kt' };

test('跨目录后返回恢复每个文件的阅读位置，旁支自动收起', () => {
  let state = createNavigation(snapshot);
  state = remember(state, { treeTop: 42, codeTop: 360, selectedLine: 31 });
  state = navigate(state, 'chat/state/b.kt', entries);
  assert.ok(isExpanded('chat/state', currentPath(state)));
  assert.ok(!isExpanded('chat/export', currentPath(state)));
  state = remember(state, { treeTop: 70, codeTop: 200, selectedLine: 16 });
  state = navigate(state, 'shared/koog/c.kt', entries);
  state = travel(travel(state, -1), -1);
  assert.equal(currentPath(state), snapshot.initial);
  assert.deepEqual(state.positions[currentPath(state)], { treeTop: 42, codeTop: 360, selectedLine: 31 });
  assert.ok(isExpanded('chat/export', currentPath(state)));
  assert.ok(!isExpanded('chat/state', currentPath(state)));
});

test('从历史中换新文件会丢弃前进分支，重复点击和未知路径不增加历史', () => {
  let state = navigate(createNavigation(snapshot), 'chat/state/b.kt', entries);
  state = navigate(travel(state, -1), 'shared/koog/c.kt', entries);
  assert.deepEqual(state.history, [snapshot.initial, 'shared/koog/c.kt']);
  assert.equal(navigate(state, 'unknown', entries), state);
  assert.equal(navigate(state, currentPath(state), entries), state);
  assert.equal(travel(state, 10).index, 1);
  assert.equal(travel(state, -10).index, 0);
});

test('刷新恢复与损坏记录降级，路径前缀不会错误展开同名目录', () => {
  const state = navigate(createNavigation(snapshot), 'shared/koog/c.kt', entries);
  assert.deepEqual(createNavigation(snapshot, JSON.parse(JSON.stringify(state))), state);
  assert.equal(currentPath(createNavigation(snapshot, { history: ['removed'], index: 10 })), snapshot.initial);
  assert.equal(isExpanded('chat/state', 'chat/stateful/a.kt'), false);
  const blocked = { getItem() { throw Error('blocked'); }, setItem() { throw Error('blocked'); } };
  assert.equal(readSaved(blocked, 'key'), null);
  assert.equal(writeSaved(blocked, 'key', state), false);
  assert.equal(readSaved({ getItem: () => '{broken' }, 'key'), null);
});

test('三份教程的来源与资源完整，模拟高亮都在有效行内', () => {
  const ids = Object.fromEntries(Object.keys(sources).map(key => [key, `src_${key}`]));
  for (const lesson of lessons) {
    const payload = tutorialPayload(lesson, ids);
    assert.ok(!payload.markdown.includes('undefined'));
    assert.ok(payload.diagrams.every(diagram => diagram.graph.nodes.length <= 8));
    for (const block of lesson.blocks) {
      if (block.source) assert.ok(sources[block.source]);
      if (block.diagram) assert.ok(lesson.diagrams.some(diagram => diagram.id === block.diagram));
      if (block.simulation) assert.ok(lesson.simulations.some(simulation => simulation.id === block.simulation));
    }
    for (const simulation of lesson.simulations) {
      const count = simulation.code.split('\n').length;
      for (const scenario of simulation.scenarios) for (const step of scenario.steps) assert.ok(step.highlightLines.every(line => Number.isInteger(line) && line > 0 && line <= count));
    }
  }
});
