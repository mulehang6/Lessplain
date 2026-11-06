// 正文与教学资源共用一份数据，供 Lessplain 教程与离线试读页使用。
export const sources = {
  send: { path: 'desktopApp/src/main/kotlin/com/agent/app/chat/state/ChatRunController.kt', context: 'sendMessageUseCase(preparedRequest).collect { event ->' },
  delta: { path: 'desktopApp/src/main/kotlin/com/agent/app/chat/state/AgentEventReducer.kt', context: 'is AgentStreamEvent.TextDelta -> appendAssistantTextHistory(' },
  tools: { path: 'shared/src/jvmMain/kotlin/com/agent/shared/agent/koog/KoogStreamingStrategy.kt', context: 'val nodeExecuteTool by nodeExecuteTools()' },
  result: { path: 'shared/src/jvmMain/kotlin/com/agent/shared/agent/koog/KoogStreamingStrategy.kt', context: 'toolResult(toolResult.toMessagePart())' },
  finish: { path: 'shared/src/jvmMain/kotlin/com/agent/shared/agent/koog/KoogAssistantMessageMapper.kt', context: 'parts.none { it is MessagePart.Tool.Call } &&' },
  cancel: { path: 'desktopApp/src/main/kotlin/com/agent/app/chat/state/ChatRunController.kt', context: 'runningJob?.cancel()' },
  stop: { path: 'shared/src/jvmMain/kotlin/com/agent/shared/agent/koog/KoogAgentGateway.kt', context: 'if (stopResult.decision != AgentHookDecision.BLOCK) return result' },
};

export const lessons = [
  {
    title: 'Agent 流程 1/3：一句话怎样变成回复',
    blocks: [
      { text: '这次只跟一句“你好”。读完这一遍就可以停。之后还有“调用一次工具”和“怎样停下来”，现在不用展开。' },
      { diagram: 'reply' },
      { text: '应用把你写的话和必要信息装进一次请求，交给执行入口。模型返回的文字可能分成许多小段。每收到一小段，应用就把它接到当前回复后面，所以你能看到文字逐渐出现。' },
      { simulation: 'reply' },
      { text: '源码里把这些小通知叫 event，意思是“刚刚发生了一件事”。TextDelta 表示“又收到一小段文字”。先认出这一个就够了。' },
      { source: 'send', label: '看收消息的入口，只看 collect 这一处和下一行' },
      { source: 'delta', label: '看一小段文字怎样接到回复后面' },
      { text: '到这里先停。你已经能说明：发出一句话，接收文字片段，把片段接成回复。类名记不住也没关系。' },
      { text: '可以自己想一下：文字为什么能一点点出现？如果仍然只能复述类名，就选上一段说“这段没懂，请换个例子”，不用先找出自己缺哪个知识。' },
      { text: '想继续时，在教程目录打开“Agent 流程 2/3”。本遍只看新发送、正常返回且没有工具调用的路径；资源准备、存储、Hook、恢复与错误留在范围外。图与模拟均来自静态源码整理，不是本次运行记录。' },
    ],
    diagrams: [{ id: 'reply', graph: {
      nodes: [{ id: 'input', label: '发出“你好”', sourceKey: 'send' }, { id: 'model', label: '模型逐段回复' }, { id: 'screen', label: '文字接到屏幕上', sourceKey: 'delta' }],
      edges: [{ id: 'request', from: 'input', to: 'model', label: '发送请求' }, { id: 'pieces', from: 'model', to: 'screen', label: '每收到一小段' }],
    } }],
    simulations: [{ id: 'reply', language: 'text', code: '发出：“你好”\n收到一段：“你”\n收到一段：“好！”\n本次请求完成', scenarios: [{ id: 'hello', label: '只看一次普通回复', steps: [
      { highlightLines: [1], caption: '先把用户的话交给执行入口。' },
      { highlightLines: [2], caption: '屏幕先出现第一个片段。', stdout: '你' },
      { highlightLines: [3], caption: '第二个片段接在后面，合起来是“你好！”。', stdout: '好！' },
      { highlightLines: [4], caption: '这个例子没有工具调用，正常完成。实际分段方式由服务端决定。' },
    ] }] }],
  },
  {
    title: 'Agent 流程 2/3：读一次文件后，为什么还要问模型',
    blocks: [
      { text: '接着上一遍，只多一件事。你问“README 说这个项目做什么”，模型需要先看到文件。这里假定工具已可用，权限允许，读取成功。' },
      { diagram: 'tool' },
      { text: '模型先交回“请读取 README”的请求。应用执行读取，把文件内容送回模型。模型看到内容后，才组织回答。文件是应用里的工具读的；工具结果还要送回模型。' },
      { simulation: 'read' },
      { source: 'tools', label: '看执行工具的节点 nodeExecuteTools' },
      { source: 'result', label: '看工具结果放回模型输入的位置' },
      { text: '如果模型接着还要求调用工具，就再走一遍“执行、送回结果”。因此，一个用户问题可能包含几次模型请求。图里的回路代表可能继续，不代表本次真的运行过。' },
      { text: '到这里先停。试着在图里指出：文件内容在哪一步交回模型？知道这一处，就抓住了工具循环里新增的关系。' },
      { text: '仍然混乱时，只比较两句话：“模型请求读取文件”和“工具已经读到了文件”。它们发生在不同时间。想继续时打开“Agent 流程 3/3”；权限询问、工具失败、多工具和恢复暂时不展开。模拟里的 README 内容是预设例子。' },
    ],
    diagrams: [{ id: 'tool', graph: {
      nodes: [{ id: 'ask', label: '模型请求读文件' }, { id: 'execute', label: '应用执行读取', sourceKey: 'tools' }, { id: 'return', label: '文件内容交回模型', sourceKey: 'result' }, { id: 'answer', label: '模型继续回复' }],
      edges: [{ id: 'call', from: 'ask', to: 'execute', label: '工具请求' }, { id: 'read', from: 'execute', to: 'return', label: '工具结果' }, { id: 'continue', from: 'return', to: 'answer', label: '再次请求模型' }, { id: 'again', from: 'answer', to: 'execute', label: '仍要求工具时', condition: '模型回复仍含工具调用' }],
    } }],
    simulations: [{ id: 'read', language: 'text', code: '用户：README 说这个项目做什么？\n模型：请读取 README.md\n应用：执行读取\n工具结果：一个桌面 Agent 应用\n应用：把工具结果送回模型\n模型：这是一个桌面 Agent 应用。', scenarios: [{ id: 'read-once', label: '读取成功后回答', steps: [
      { highlightLines: [1], caption: '用户的问题还没有得到回答。' },
      { highlightLines: [2], caption: '模型提出读取请求。此时还没有读到文件。' },
      { highlightLines: [3, 4], caption: '应用执行工具，得到文件内容。' },
      { highlightLines: [5], caption: '内容交回模型，这一步使模型能根据文件回答。' },
      { highlightLines: [6], caption: '模型看到内容后组织回复。这是教学情境，不是真实模型输出。' },
    ] }] }],
  },
  {
    title: 'Agent 流程 3/3：完成、等待和停止有什么区别',
    blocks: [
      { text: '前两遍已经走过“直接回复”和“先调用工具”。这一遍只认清几个停点，不把异常处理全部展开。' },
      { text: '模型回复有正文，而且不再含工具请求，当前工具循环就可以结束。文字已经出现，也可能仍有工具请求，所以“看到文字”不等于“整轮完成”。' },
      { source: 'finish', label: '看结束判断的两个条件，连同下一行一起看' },
      { text: '循环结束后，应用还会运行 Stop Hook。Hook 是配置在结束时执行的检查。它可以要求补充处理；通过后才发 Completed，表示整轮完成。' },
      { source: 'stop', label: '看结束检查何时放行' },
      { text: '工具需要你批准或回答时，运行会等待输入。你作出选择后，它可以在同一轮继续。等待没有把这轮标成完成。' },
      { text: '你点停止时，界面会取消当前运行任务，清掉挂起交互并回到可输入状态。它不会把已经执行的文件修改自动撤销，也不保证外部操作瞬间中止。' },
      { source: 'cancel', label: '看停止动作取消了哪个任务' },
      { text: '模型或执行过程出错时，应用显示失败；工具出错则有单独的工具失败事件，不能只看一个工具失败就断言整轮已经结束。' },
      { text: '到这里先停。看到文字、等你批准、整轮完成、你主动停止，现在能分开了吗？不需要记住状态类型名称。' },
      { text: '这三遍是完整流程的入门顺序。仍未展开的区域包括资源装配、历史与持久化、上下文压缩、工具权限、Hook 细节、断点恢复和服务端差异。它们继续存在；本次先验证这个入口是否足够容易读。' },
    ],
    diagrams: [], simulations: [],
  },
];

/** 每次保存前传入插件刚解析出的 sourceId，不在模板里固定本地记录 ID。 */
export function tutorialPayload(lesson, sourceIds) {
  const markdown = lesson.blocks.map(block => {
    if (block.text) return block.text;
    if (block.source) return `[${block.label}](lessplain://source/${sourceIds[block.source]})`;
    if (block.diagram) return `![流程概览](lessplain://diagram/${block.diagram})`;
    return `![预设教学情境](lessplain://simulation/${block.simulation})`;
  }).join('\n\n');
  return {
    title: lesson.title, markdown,
    diagrams: lesson.diagrams.map(diagram => ({ ...diagram, graph: { ...diagram.graph, nodes: diagram.graph.nodes.map(({ sourceKey, ...node }) => sourceKey ? { ...node, sourceId: sourceIds[sourceKey] } : node) } })),
    simulations: lesson.simulations,
  };
}
