import type { ReactNode } from "react";
import { resolveAnchor, type Frame, type Source } from "./model";

export default function Sidebar({
  frames,
  current,
  sources,
  activeFile,
  collapsed,
  onFrame,
  onFile,
  onToggle,
  onProject,
}: {
  frames: Frame[];
  current: string;
  sources: Source[];
  activeFile: string;
  collapsed: string[];
  onFrame: (id: string) => void;
  onFile: (id: string) => void;
  onToggle: (id: string) => void;
  onProject: () => void;
}) {
  const renderFrame = (frame: Frame, depth = 0): ReactNode => {
    const children = frames.filter((child) => child.parentId === frame.id);
    const folded = collapsed.includes(frame.id);
    return (
      <div key={frame.id}>
        <div
          className={`question-row ${current === frame.id ? "active" : ""}`}
          style={{ paddingLeft: 8 + depth * 14 }}
        >
          <button
            className="disclosure"
            aria-label={`${folded ? "展开" : "折叠"} ${frame.title}`}
            onClick={() => onToggle(frame.id)}
            disabled={!children.length}
          >
            {children.length ? (folded ? "›" : "⌄") : "·"}
          </button>
          <button
            className="question-title"
            onClick={() => onFrame(frame.id)}
            title={frame.title}
          >
            <span>{frame.title}</span>
            {frame.nodes.some(
              (n) =>
                resolveAnchor(
                  n.anchor,
                  sources.find((s) => s.id === n.anchor.file)!,
                ).evidenceStale,
            ) && <small className="amber">代码有变化</small>}
          </button>
        </div>
        {!folded && children.map((child) => renderFrame(child, depth + 1))}
      </div>
    );
  };
  return (
    <aside className="sidebar">
      <div className="panel-heading">
        <span>Project</span>
        <span className="subtle">职责注释</span>
      </div>
      <div className="project-tree">
        <div className="tree-root">
          ⌄ <span className="folder-icon">▰</span> mulehang-agent
        </div>
        <div className="tree-folder">
          <span>⌄ desktopApp</span>
          <small>桌面 UI / 状态</small>
        </div>
        <div className="tree-folder nested">
          <span>⌄ chat / state</span>
          <small>会话入口</small>
        </div>
        <button
          className={`tree-file ${activeFile === "branch" ? "active" : ""}`}
          onClick={() => onFile("branch")}
        >
          <span className="kotlin">K</span> {sources[0].name}
        </button>
        <div className="tree-folder">
          <span>⌄ shared</span>
          <small>共享逻辑</small>
        </div>
        <div className="tree-folder nested">
          <span>⌄ persistence</span>
          <small>快照 / 重试</small>
        </div>
        <button
          className={`tree-file ${activeFile === "snapshot" ? "active" : ""}`}
          onClick={() => onFile("snapshot")}
        >
          <span className="kotlin">K</span> {sources[1].name}
        </button>
        <div className="tree-folder nested">
          <span>⌄ agent</span>
          <small>模型 / 工具</small>
        </div>
        <button
          className={`tree-file ${activeFile === "strategy" ? "active" : ""}`}
          onClick={() => onFile("strategy")}
        >
          <span className="kotlin">K</span> {sources[2].name}
        </button>
        <div className="tree-folder muted">
          <span>› build</span>
          <small>生成内容</small>
        </div>
        <div className="tree-folder muted">
          <span>› External Libraries</span>
        </div>
        <button className="project-link" onClick={onProject}>
          <span>◇</span> 查看项目关系图 <span>↗</span>
        </button>
      </div>
      <div className="panel-heading">
        <span>理解记录</span>
        <span className="subtle">{frames.length} 个问题</span>
      </div>
      <nav className="question-tree" aria-label="历史理解问题">
        {frames
          .filter((frame) => frame.parentId === null)
          .map((frame) => renderFrame(frame))}
      </nav>
      <div className="sidebar-note">
        在自己的 Agent 中提问。
        <br />
        这里保留问题之间的关系与当时的视图。
      </div>
    </aside>
  );
}
