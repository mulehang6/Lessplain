import { useEffect, useState } from "react";
import {
  currentSources,
  initialFrames,
  projectEvidence,
  projectGraph,
} from "./data";
import { replay, type GraphNode } from "./model";
import { readSession, STORAGE_KEY } from "./session";
import { followupScene, updatedBranchScene } from "./scenario-model";
import Graph from "./Graph";
import Editor from "./Editor";
import Sidebar from "./Sidebar";
import ChangeReview from "./ChangeReview";
import { ScenePicker, SceneStory, type Scene } from "./Scenes";

export default function App() {
  const [session, setSession] = useState(readSession);
  const [picker, setPicker] = useState(false);
  const [scene, setScene] = useState<Scene | null>(null);
  const [reviewNode, setReviewNode] = useState<GraphNode | null>(null);
  const [answered, setAnswered] = useState(false);
  const [notice, setNotice] = useState("点图节点看源码，回放也在图里");
  const [copyFallback, setCopyFallback] = useState("");
  const sourceFiles = currentSources(session.mode);
  const frame = session.frames.find((f) => f.id === session.active)!;
  const visit = session.visits[frame.id];
  const inProject = session.view === "project";
  const shownFrame = inProject ? projectGraph : frame;
  const shownVisit = inProject
    ? {
        nodeId: session.projectNode,
        cursor: -1,
        file: projectGraph.nodes.find((n) => n.id === session.projectNode)!
          .anchor.file,
      }
    : visit;
  const selected = shownFrame.nodes.find(
    (node) => node.id === shownVisit.nodeId,
  )!;
  const reviewed =
    session.frames
      .find((f) => f.id === "A")
      ?.nodes.find((n) => n.id === "child")?.anchor.revision === "demo-r2";

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } catch {
      setNotice("浏览器不允许本地保存，刷新后状态可能丢失");
    }
  }, [session]);

  function chooseFrame(id: string) {
    setSession((s) => ({ ...s, active: id, view: "question" }));
    setReviewNode(null);
  }
  function chooseNode(id: string) {
    if (inProject) {
      setSession((s) => ({ ...s, projectNode: id }));
      return;
    }
    const node = frame.nodes.find((n) => n.id === id)!;
    setSession((s) => ({
      ...s,
      visits: {
        ...s.visits,
        [frame.id]: {
          ...s.visits[frame.id],
          nodeId: id,
          file: node.anchor.file,
        },
      },
    }));
  }
  function chooseFile(file: string) {
    if (file === "strategy") {
      setSession((s) => ({ ...s, view: "project", projectNode: "agent" }));
      return;
    }
    const target = frame.nodes.find((node) => node.anchor.file === file);
    if (target && !inProject) {
      chooseNode(target.id);
      return;
    }
    chooseFrame(file === "snapshot" ? "R" : "A");
  }
  function step(cursor: number) {
    const bounded = Math.max(
      -1,
      Math.min(cursor, (frame.trace?.hits.length ?? 0) - 1),
    );
    const hit = frame.trace?.hits[bounded];
    const node =
      frame.nodes.find((n) => n.id === hit?.nodeId) ?? frame.nodes[0];
    setSession((s) => ({
      ...s,
      visits: {
        ...s.visits,
        [frame.id]: {
          cursor: bounded,
          nodeId: node.id,
          file: node.anchor.file,
        },
      },
    }));
  }
  async function copyQuestion(question: string, node = selected) {
    const source = sourceFiles.find((s) => s.id === node.anchor.file)!;
    const hit = inProject
      ? undefined
      : replay(frame.trace, visit.cursor).latest.get(node.id);
    const text = `${question}\n当前问题：${shownFrame.title}\n关注节点：${node.title}\n源码：${source.name} / ${node.anchor.symbol}\n原代码：${node.anchor.text}\n当前源码版本：${source.revision}${hit ? `\n历史记录：${frame.trace!.id}，命中 #${hit.seq}` : ""}`;
    try {
      await navigator.clipboard.writeText(text);
      setNotice("已复制问题和位置 → 粘贴到你的 Agent，收到回答后再回来看图");
    } catch {
      setCopyFallback(text);
      setNotice("请复制弹窗中的问题，粘贴到你的 Agent");
    }
  }
  function inspectChange(id: string) {
    chooseNode(id);
    setReviewNode(shownFrame.nodes.find((node) => node.id === id)!);
  }
  function returnToOrigin() {
    if (!frame.origin) return;
    const origin = frame.origin;
    const target = session.frames.find((f) => f.id === origin.frameId);
    if (!target) {
      setNotice("来源问题已不在本地记录中");
      return;
    }
    const cursor =
      target.trace?.id === origin.traceId
        ? target.trace.hits.findIndex((hit) => hit.seq === origin.seq)
        : -1;
    setSession((s) => ({
      ...s,
      active: target.id,
      view: "question",
      visits: {
        ...s.visits,
        [target.id]: {
          nodeId: origin.nodeId,
          cursor,
          file: origin.sourceAnchor.file,
        },
      },
    }));
  }
  function chooseScene(next: Scene) {
    setScene(next);
    setPicker(false);
    setReviewNode(null);
    setAnswered(false);
    const id = next === "retry" ? "R" : "A";
    const nodeId =
      next === "retry"
        ? "save"
        : next === "changed"
          ? "child"
          : next === "followup"
            ? "show"
            : "entry";
    setSession((s) => ({
      ...s,
      active: id,
      view: "question",
      mode: next === "changed" ? "missing" : "original",
      frames: s.frames.map((f) =>
        f.id === "A" ? structuredClone(initialFrames[0]) : f,
      ),
      collapsed: s.collapsed.filter((x) => x !== "A"),
      visits: {
        ...s.visits,
        [id]: {
          nodeId,
          cursor: next === "retry" ? 0 : next === "followup" ? 3 : -1,
          file: next === "retry" ? "snapshot" : "branch",
        },
      },
    }));
    setNotice(
      next === "retry"
        ? "点保存节点上的 1 / 2 / 3，切换每次重试"
        : next === "changed"
          ? "图上的橙色节点对应改过的代码，点「查看变化」"
          : next === "followup"
            ? "先看问题从哪里产生，再看回答接到哪里"
            : "用图内的回放按钮沿执行路径前进",
    );
  }
  function receiveUpdate() {
    const updated = updatedBranchScene();
    setSession((s) => ({
      ...s,
      mode: "missing",
      active: "A",
      view: "question",
      frames: s.frames.map((f) => (f.id === "A" ? updated : f)),
      visits: {
        ...s.visits,
        A: { nodeId: "child", cursor: -1, file: "branch" },
      },
    }));
    setReviewNode(null);
    setNotice(
      "演示：Agent 已核对新源码，创建子会话节点已更新；旧运行记录保留原版本",
    );
  }
  function receiveAnswer() {
    const result = followupScene();
    setSession((s) => ({
      ...s,
      active: result.id,
      view: "question",
      frames: s.frames.some((f) => f.id === result.id)
        ? s.frames.map((f) => (f.id === result.id ? result : f))
        : [...s.frames, result],
      collapsed: s.collapsed.filter((id) => id !== "A"),
      visits: {
        ...s.visits,
        B: { nodeId: "show", cursor: -1, file: "branch" },
      },
    }));
    setAnswered(true);
    setNotice(
      "草稿的回答已接在「创建新分支」下面。点「回到提问处」可返回原来的节点。",
    );
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-mark">u</div>
        <b>Understanding</b>
        <span className="header-divider" />
        <span>mulehang-agent</span>
        <span className="badge">原型 v0.5</span>
        <div className="header-spacer" />
        <span className="header-note">示例源码 · 合成回放</span>
        <button
          aria-expanded={picker}
          onClick={() => setPicker((open) => !open)}
        >
          体验场景 ⌄
        </button>
      </header>
      {picker && (
        <ScenePicker onChoose={chooseScene} onClose={() => setPicker(false)} />
      )}
      <SceneStory
        scene={scene}
        reviewed={reviewed}
        answered={answered}
        onUpdate={receiveUpdate}
        onAnswer={receiveAnswer}
        onClose={() => setScene(null)}
      />
      <main className="workspace">
        <Sidebar
          frames={session.frames}
          current={frame.id}
          sources={sourceFiles}
          activeFile={shownVisit.file}
          collapsed={session.collapsed}
          onFrame={chooseFrame}
          onFile={chooseFile}
          onProject={() => setSession((s) => ({ ...s, view: "project" }))}
          onToggle={(id) =>
            setSession((s) => ({
              ...s,
              collapsed: s.collapsed.includes(id)
                ? s.collapsed.filter((x) => x !== id)
                : [...s.collapsed, id],
            }))
          }
        />
        <Editor
          frame={shownFrame}
          visit={shownVisit}
          sources={sourceFiles}
          onSelect={chooseNode}
          onFile={chooseFile}
        />
        <aside className="understanding-panel">
          <div className="view-tabs" aria-label="理解视图">
            <button
              aria-pressed={!inProject}
              className={!inProject ? "active" : ""}
              onClick={() => setSession((s) => ({ ...s, view: "question" }))}
            >
              当前理解
            </button>
            <button
              aria-pressed={inProject}
              className={inProject ? "active" : ""}
              onClick={() => setSession((s) => ({ ...s, view: "project" }))}
            >
              项目关系
            </button>
          </div>
          <div className="frame-heading">
            {!inProject && frame.parentId && (
              <button
                className="parent-breadcrumb"
                onClick={() => chooseFrame(frame.parentId!)}
              >
                {session.frames.find((f) => f.id === frame.parentId)?.title} ›
              </button>
            )}
            <h1>{shownFrame.title}</h1>
            {!inProject && frame.origin && (
              <button className="origin-link" onClick={returnToOrigin}>
                ↖ 回到提问处
              </button>
            )}
          </div>
          <Graph
            key={shownFrame.id}
            frame={shownFrame}
            selected={selected.id}
            cursor={shownVisit.cursor}
            sources={sourceFiles}
            onSelect={chooseNode}
            onStep={step}
            onChange={inspectChange}
            onQuestion={(question) => void copyQuestion(question)}
            evidence={inProject ? projectEvidence[selected.id] : undefined}
          />
        </aside>
      </main>
      <footer className="statusbar">
        <span role="status">
          <span className="small-dot green" />
          {notice}
        </span>
        <span>自动保存</span>
      </footer>
      {reviewNode && (
        <ChangeReview
          node={reviewNode}
          source={sourceFiles.find((s) => s.id === reviewNode.anchor.file)!}
          onClose={() => setReviewNode(null)}
          onCopy={() => {
            void copyQuestion(
              "这段代码变了，请比较新旧实现，更新对应图节点、关系和源码位置；保留旧运行记录的版本标记。",
              reviewNode,
            );
            setReviewNode(null);
          }}
        />
      )}
      {copyFallback && (
        <div className="modal-backdrop">
          <section
            role="dialog"
            aria-modal="true"
            aria-label="手动复制问题"
            className="copy-dialog"
          >
            <h2>复制后，粘贴到你的 Agent</h2>
            <textarea
              autoFocus
              readOnly
              value={copyFallback}
              onFocus={(event) => event.currentTarget.select()}
            />
            <button onClick={() => setCopyFallback("")}>关闭</button>
          </section>
        </div>
      )}
    </div>
  );
}
