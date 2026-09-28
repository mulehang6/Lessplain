import type {
  Anchor,
  Frame,
  GraphNode,
  Hit,
  Source,
  SourceMode,
} from "./model";

export const sources: Source[] = [
  {
    id: "branch",
    name: "BranchExample.kt",
    revision: "demo-r1",
    symbol: "createBranch",
    role: "教学示例 · 调用顺序参考已核对的会话分支实现，省略业务细节",
    lines: [
      "// 示例代码，不是项目源码副本",
      "fun createBranch(entryId: String) {",
      "    val point = requireEntry(entryId)",
      "    if (!point.canBranch) {",
      "        return",
      "    }",
      "",
      "    val child = Conversation(",
      "        parentId = point.conversationId,",
      "        entries = copyPath(point),",
      "    ).withEntryProjection()",
      "",
      "    window.ui = window.ui.add(child)",
      "    window.showExistingConversation(",
      "        child.id, restoredDraft = point.draft,",
      "    )",
      "    persistenceCoordinator?.schedule(window.ui.tasks)",
      "}",
    ],
  },
  {
    id: "snapshot",
    name: "SnapshotExample.kt",
    revision: "demo-r1",
    symbol: "saveWithRetry",
    role: "教学示例 · 同一个保存节点重试三次，分别保留每次结果",
    lines: [
      "// 合成重试场景，用来演示多次命中",
      "fun saveWithRetry(snapshot: Snapshot) {",
      "    var attempt = 0",
      "    while (attempt < 3) {",
      "        attempt += 1",
      "        val result = store.trySave(snapshot)",
      "        if (result.ok) {",
      "            return",
      "        }",
      "        backoff(attempt)",
      "    }",
      '    error("保存失败")',
      "}",
    ],
  },
  {
    id: "strategy",
    name: "AgentExample.kt",
    revision: "demo-r1",
    symbol: "runAgent",
    role: "教学示例 · 项目关系图中的 Agent Runtime 入口",
    lines: [
      "// 聚合区域的候选入口示例",
      "fun runAgent(request: Request) {",
      "    val response = model.respond(request)",
      "    val result = tools.execute(response.toolCall)",
      "    session.append(result)",
      "}",
    ],
  },
];

export function anchor(file: string, line: number): Anchor {
  const source = sources.find((s) => s.id === file)!;
  return {
    file,
    symbol: source.symbol,
    text: source.lines[line - 1].trim(),
    lineHint: line,
    revision: source.revision,
  };
}

const node = (
  id: string,
  title: string,
  detail: string,
  file: string,
  line: number,
  question?: string,
): GraphNode => ({ id, title, detail, anchor: anchor(file, line), question });

const branchNodes = [
  node("entry", "定位历史节点", "entryId → point", "branch", 3),
  node("guard", "判断能否分支", "point.canBranch", "branch", 4),
  node(
    "child",
    "创建子会话",
    "复制路径，构造 child",
    "branch",
    8,
    "为什么要创建新的 child，而不直接修改原会话？",
  ),
  node("reject", "结束本次调用", "不满足分支条件", "branch", 5),
  node(
    "show",
    "切换会话并带上草稿",
    "showExistingConversation",
    "branch",
    14,
    "切换会话时，草稿是怎样一起恢复的？",
  ),
  node(
    "schedule",
    "安排持久化",
    "schedule(tasks)",
    "branch",
    17,
    "安排持久化之后，实际写入什么时候完成？",
  ),
];

const hit = (
  nodeId: string,
  seq: number,
  values: Record<string, string>,
  file: string,
  observationLine: number,
): Hit => ({ nodeId, seq, values, observation: anchor(file, observationLine) });

export const initialFrames: Frame[] = [
  {
    id: "A",
    parentId: null,
    origin: null,
    title: "从历史节点创建新分支怎么走？",
    subtitle:
      "先切换到新会话，再安排持久化。schedule 的命中不代表磁盘写入完成。",
    nodes: branchNodes,
    edges: [
      { from: "entry", to: "guard", label: "历史节点" },
      { from: "guard", to: "child", label: "允许" },
      { from: "guard", to: "reject", label: "拒绝" },
      { from: "child", to: "show", label: "child.id" },
      { from: "show", to: "schedule", label: "调用返回" },
    ],
    trace: {
      id: "branch-demo-01",
      revision: "demo-r1",
      lane: "单线程 · 一次调用",
      hits: [
        hit("entry", 1, { entryId: "entry-42" }, "branch", 4),
        hit("guard", 2, { canBranch: "true" }, "branch", 8),
        hit("child", 3, { "child.id": "conv-b" }, "branch", 13),
        hit("show", 4, { active: "conv-b", draft: "已交给窗口" }, "branch", 17),
        hit(
          "schedule",
          5,
          { schedule: "即将调用", diskWrite: "未观测" },
          "branch",
          17,
        ),
      ],
    },
  },
  {
    id: "B",
    parentId: "A",
    title: "草稿如何随会话一起恢复？",
    subtitle: "这份历史理解只展开窗口接收 child.id 和 restoredDraft 的位置。",
    origin: {
      frameId: "A",
      nodeId: "show",
      sourceAnchor: anchor("branch", 14),
      traceId: "branch-demo-01",
      seq: 4,
    },
    nodes: [branchNodes[2], branchNodes[4]],
    edges: [{ from: "child", to: "show", label: "child.id + draft" }],
  },
  {
    id: "C",
    parentId: "A",
    title: "切换后安排持久化，意味着什么？",
    subtitle: "与草稿问题同级。这里区分调用顺序与持久化完成时间。",
    origin: {
      frameId: "B",
      nodeId: "show",
      sourceAnchor: anchor("branch", 14),
      traceId: null,
      seq: null,
    },
    nodes: [branchNodes[4], branchNodes[5]],
    edges: [{ from: "show", to: "schedule", label: "随后调用" }],
  },
  {
    id: "R",
    parentId: null,
    origin: null,
    title: "重试三次，每次保存发生了什么？",
    subtitle:
      "同一个保存节点命中 3 次。拖动回放进度，看 attempt 和 result 如何变化。",
    nodes: [
      node("save", "尝试保存", "store.trySave(snapshot)", "snapshot", 6),
      node("check", "检查本次结果", "result.ok", "snapshot", 7),
      node("retry", "等待后重试", "backoff(attempt)", "snapshot", 10),
      node("done", "保存成功，返回", "return", "snapshot", 8),
    ],
    edges: [
      { from: "save", to: "check", label: "本次结果" },
      { from: "check", to: "retry", label: "失败" },
      { from: "retry", to: "save", label: "下一次尝试" },
      { from: "check", to: "done", label: "成功" },
    ],
    trace: {
      id: "retry-demo-01",
      revision: "demo-r1",
      lane: "单线程 · 同一次调用",
      hits: [
        hit("save", 1, { attempt: "1", result: "timeout" }, "snapshot", 7),
        hit("check", 2, { ok: "false" }, "snapshot", 7),
        hit("retry", 3, { backoff: "100 ms" }, "snapshot", 10),
        hit("save", 4, { attempt: "2", result: "busy" }, "snapshot", 7),
        hit("check", 5, { ok: "false" }, "snapshot", 7),
        hit("retry", 6, { backoff: "200 ms" }, "snapshot", 10),
        hit("save", 7, { attempt: "3", result: "ok" }, "snapshot", 7),
        hit("check", 8, { ok: "true" }, "snapshot", 7),
        hit("done", 9, { outcome: "即将返回" }, "snapshot", 8),
      ],
    },
  },
];

export function currentSources(mode: SourceMode): Source[] {
  return sources.map((source) => {
    if (source.id !== "branch" || mode === "original") return source;
    const lines = [...source.lines];
    if (mode === "moved") lines.unshift("// 模拟：文件顶部新增注释", "");
    if (mode === "missing")
      lines.splice(7, 4, "    val child = buildBranch(point)", "", "", "");
    if (mode === "ambiguous") lines.push("    val child = Conversation(");
    return { ...source, revision: "demo-r2", lines };
  });
}

export const projectGraph: Frame = {
  id: "project",
  title: "项目关系",
  subtitle: "目录是线索，聚合区域需要结构证据。以下为示例关系。",
  parentId: null,
  origin: null,
  nodes: [
    node("desktop", "Desktop UI", "窗口、会话入口", "branch", 14),
    node("session", "Session", "会话树、历史分支", "branch", 8),
    node("agent", "Agent Runtime", "模型调用、工具执行", "strategy", 2),
    node("persistence", "Persistence", "快照、保存与重试", "snapshot", 6),
  ],
  edges: [
    { from: "desktop", to: "session", label: "发起分支" },
    { from: "desktop", to: "agent", label: "提交请求" },
    { from: "session", to: "persistence", label: "安排保存" },
  ],
};

export const projectEvidence: Record<
  string,
  { scope: string; evidence: string; question: string }
> = {
  desktop: {
    scope: "desktopApp / chat",
    evidence: "结构事实示例：窗口代码依赖会话控制器。聚合名称由 Agent 给出。",
    question: "从 Desktop UI 发起一次新会话，会经过哪些模块？",
  },
  session: {
    scope: "shared / session + desktopApp / chat / state",
    evidence: "跨目录聚合示例：共享会话模型与桌面控制器共同参与分支流程。",
    question: "Session 的共享模型与桌面控制器各负责什么？",
  },
  agent: {
    scope: "shared / agent",
    evidence: "结构事实示例：模型响应进入工具执行，再写入会话。",
    question: "一次工具调用的结果怎样回到会话？",
  },
  persistence: {
    scope: "shared / persistence",
    evidence: "结构事实示例：会话状态触发快照保存。保存的调用不等于完成。",
    question: "持久化失败后会发生什么，谁负责重试？",
  },
};
