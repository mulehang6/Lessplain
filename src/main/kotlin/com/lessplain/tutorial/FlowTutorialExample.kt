package com.lessplain.tutorial

/** 用户显式打开的全新格式示例，不覆盖已有教材和批注。 */
internal fun createFlowExample(service: TutorialService): TutorialRecord {
    val source = runCatching { service.sources.resolve(
        "shared/src/jvmMain/kotlin/com/agent/shared/agent/koog/KoogAgentGateway.kt",
        "override fun run(request: AgentRunRequest): Flow<AgentStreamEvent>", "Flow<AgentStreamEvent>",
    ) }.getOrNull()
    val context = service.store.saveContext(TutorialContext("ctx_builtin_flow", "Flow 什么时候开始执行？", listOfNotNull(source?.id)))
    val sourceLink = source?.let { "[查看项目返回类型](lessplain://source/${it.id})" }.orEmpty()
    val simulation = TutorialSimulation("cold_flow", "kotlin", """
        suspend fun demo(collect: Boolean) {
            val f = flow {
                println("开始生产")
                emit("你好")
            }
            if (collect) f.collect { println(it) }
        }
    """.trimIndent(), listOf(
        SimulationScenario("not_collected", "先不接收：collect=false", listOf(
            SimulationStep(listOf(2), "把要做的事准备好，花括号里的代码还没开始。"),
            SimulationStep(listOf(6), "开关是 false，不调用 collect，所以没有输出。"),
        )),
        SimulationScenario("collected", "开始接收：collect=true", listOf(
            SimulationStep(listOf(2), "先准备好 Flow，还没有执行里面的代码。"),
            SimulationStep(listOf(6), "调用 collect，表示开始接收结果，花括号里的代码才开始执行。"),
            SimulationStep(listOf(3), "执行第一条 println，把文字显示在右边。", "开始生产\n"),
            SimulationStep(listOf(4, 6), "emit 送出“你好”，collect 后面的代码收到它，再用 println 显示。", "你好\n"),
        )),
    ))
    val graph = TutorialDiagram("delivery", TutorialGraph(listOf(TutorialNode("producer", "发出结果的代码"), TutorialNode("collector", "接收并处理结果的代码")), listOf(TutorialEdge("delivery", "producer", "collector", "每次送出一份结果"))))
    return service.create(context.id, "Flow 里的代码什么时候开始执行", """
        # Flow 里的代码什么时候开始执行

        先想一个需求：一段任务还没结束，我们就想知道它“开始了”“又生成了一段文字”“完成了”。Flow 可以把这些消息一份一份送出来，另一段代码收到后马上处理。

        项目里的 `Flow<AgentStreamEvent>` 可以先读作“一份份送出的运行消息”。`< >` 里的名字说明送出来的是什么；这里的 `AgentStreamEvent` 就是项目为这些消息起的类型名。

        $sourceLink

        | 部分 | 负责什么 |
        | --- | --- |
        | Flow | 一份一份送出结果，而不是只给出最后的结果 |
        | AgentStreamEvent | 区分“开始了”Started、“又有一段文字”TextDelta、“完成了”Completed 等消息 |

        ![事件传递](lessplain://diagram/delivery)

        ## 对比两种情境

        下面是简化的教学模拟，不会真的运行代码。`flow { ... }` 先记下要做的事，调用 `collect` 才开始执行其中的代码并接收结果。通常把这种“等有人接收才开始”的方式叫作“冷 Flow”。这是这个示例的行为，不能据此认为所有 Flow 都一样。

        例子中的 `collect` 开关为 `true` 时执行最后一行，为 `false` 时跳过。`println` 是显示文字，`emit` 是送出一份结果。用上一步、下一步比较两种情况。

        ![收集情境对比](lessplain://simulation/cold_flow)

        选中文字即可写笔记或追问。编辑、分栏、预览用标签栏右侧的图标切换；批注索引在教程右键菜单中。
    """.trimIndent(), TutorialResourceState(listOf(graph), listOf(simulation)))
}
