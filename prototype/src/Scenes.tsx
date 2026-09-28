export type Scene = "branch" | "retry" | "changed" | "followup";

const scenes: { id: Scene; title: string; diagram: string; hint: string }[] = [
  {
    id: "branch",
    title: "看一次分支",
    diagram: "○ → ◇ → ○",
    hint: "沿着图回放一次执行",
  },
  {
    id: "retry",
    title: "看三次重试",
    diagram: "① ↻ ② ↻ ③",
    hint: "在同一节点切换每次结果",
  },
  {
    id: "changed",
    title: "代码改了以后",
    diagram: "○ → △ → ○",
    hint: "对比变化，再看更新后的图",
  },
  {
    id: "followup",
    title: "继续追问草稿",
    diagram: "○ ─┬─ ○",
    hint: "看一个具体追问接到哪里",
  },
];

export function ScenePicker({
  onChoose,
  onClose,
}: {
  onChoose: (scene: Scene) => void;
  onClose: () => void;
}) {
  return (
    <section className="scene-picker" aria-label="体验场景">
      <div className="scene-picker-heading">
        <b>选一个场景试试</b>
        <span>预设演示，不会调用 Agent</span>
        <button onClick={onClose} aria-label="关闭场景选择">
          ×
        </button>
      </div>
      <div className="scene-options">
        {scenes.map((scene) => (
          <button key={scene.id} onClick={() => onChoose(scene.id)}>
            <span className="scene-diagram" aria-hidden="true">
              {scene.diagram}
            </span>
            <b>{scene.title}</b>
            <small>{scene.hint}</small>
          </button>
        ))}
      </div>
    </section>
  );
}

export function SceneStory({
  scene,
  reviewed,
  answered,
  onUpdate,
  onAnswer,
  onClose,
}: {
  scene: Scene | null;
  reviewed: boolean;
  answered: boolean;
  onUpdate: () => void;
  onAnswer: () => void;
  onClose: () => void;
}) {
  if (scene !== "changed" && scene !== "followup") return null;
  return (
    <section className="scene-story" aria-label="场景进展">
      <span className="badge">场景演示</span>
      {scene === "changed" ? (
        <>
          <span>创建会话的代码改了</span>
          <span className="story-arrow">→</span>
          <span>
            {reviewed ? "Agent 已返回新版理解" : "点图上的「查看变化」"}
          </span>
          {!reviewed && (
            <button onClick={onUpdate}>演示下一步：收到更新后的图</button>
          )}
          {reviewed && <span className="green">✓ 图已更新 · 旧回放仍保留</span>}
        </>
      ) : (
        <>
          <span className="story-question">“草稿怎样随新会话一起恢复？”</span>
          <span className="story-arrow">→</span>
          {answered ? (
            <span className="green">回答接在「创建新分支」下面</span>
          ) : (
            <button onClick={onAnswer}>演示：看 Agent 的回答</button>
          )}
        </>
      )}
      <button
        className="story-close"
        aria-label="关闭场景提示"
        onClick={onClose}
      >
        ×
      </button>
    </section>
  );
}
