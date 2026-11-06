/** 目录选择与浏览历史不依赖 DOM。快照中的路径都是项目相对路径。 */
export function createNavigation(snapshot, saved) {
  const valid = path => Object.hasOwn(snapshot.entries, path);
  const initial = snapshot.initial;
  const history = Array.isArray(saved?.history) ? saved.history.filter(valid) : [];
  const usable = history.length > 0 && history.length === saved.history.length;
  return {
    history: usable ? history : [initial],
    index: usable && Number.isInteger(saved.index) ? Math.max(0, Math.min(saved.index, history.length - 1)) : 0,
    positions: saved?.positions && typeof saved.positions === 'object' ? saved.positions : {},
  };
}

export const currentPath = state => state.history[state.index];
export const parentPath = path => path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '';
export const pathName = path => path.slice(path.lastIndexOf('/') + 1);

/** 当前路径的祖先展开；其他目录仍显示在同一父级的稳定排序位置。 */
export function isExpanded(path, current) {
  return path === '' || path === current || current.startsWith(`${path}/`);
}

export function remember(state, position) {
  return { ...state, positions: { ...state.positions, [currentPath(state)]: position } };
}

export function navigate(state, path, entries) {
  if (!Object.hasOwn(entries, path) || path === currentPath(state)) return state;
  return { ...state, history: [...state.history.slice(0, state.index + 1), path], index: state.index + 1 };
}

export function travel(state, offset) {
  return { ...state, index: Math.max(0, Math.min(state.history.length - 1, state.index + offset)) };
}

/** 保存被拒绝或记录损坏时仍允许浏览，界面说明是否已保存。 */
export function readSaved(storage, key) {
  try { return JSON.parse(storage.getItem(key) ?? 'null'); } catch { return null; }
}

export function writeSaved(storage, key, state) {
  try { storage.setItem(key, JSON.stringify(state)); return true; } catch { return false; }
}
