package com.lessplain.tutorial

/** 先验证整个提交，再修改任何磁盘或内存状态。 */
internal fun validateTutorial(markdown: String, resources: TutorialResourceState) {
    val diagrams = resources.diagrams.map { it.id }
    val simulations = resources.simulations.map { it.id }
    uniqueIds(diagrams, "diagrams")
    uniqueIds(simulations, "simulations")
    resources.diagrams.forEach { validateTutorialGraph(it.graph) }
    resources.simulations.forEach(::validateSimulation)
    Regex("lessplain://(diagram|simulation)/([^\\s)]+)").findAll(markdown).forEach { match ->
        val ids = if (match.groupValues[1] == "diagram") diagrams else simulations
        tutorialRequire(match.groupValues[2] in ids, "RESOURCE_NOT_FOUND", "markdown", "资源引用不存在：${match.value}")
    }
}

internal fun uniqueIds(ids: List<String>, field: String) {
    tutorialRequire(ids.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }, "INVALID_ID", field, "资源 ID 只允许字母、数字、点、下划线与连字符")
    tutorialRequire(ids.distinct().size == ids.size, "DUPLICATE_ID", field, "ID 不可重复")
}

internal fun validateTutorialGraph(graph: TutorialGraph) {
    uniqueIds(graph.nodes.map { it.id }, "graph.nodes")
    uniqueIds(graph.edges.map { it.id }, "graph.edges")
    uniqueIds(graph.evidence.map { it.id }, "graph.evidence")
    uniqueIds(graph.clusters.map { it.id }, "graph.clusters")
    val nodes = graph.nodes.map { it.id }.toSet()
    tutorialRequire(nodes.isNotEmpty(), "EMPTY_GRAPH", "graph.nodes", "图至少需要一个节点")
    tutorialRequire(graph.nodes.all { it.label.isNotBlank() }, "EMPTY_LABEL", "graph.nodes.label", "节点标签不能为空")
    val evidence = graph.evidence.map { it.id }.toSet()
    tutorialRequire((graph.nodes.flatMap { it.evidenceIds } + graph.edges.flatMap { it.evidenceIds }).all { it in evidence }, "INVALID_EVIDENCE", "graph.evidenceIds", "引用的证据不存在")
    if (graph.clusters.isNotEmpty()) {
        val members = graph.clusters.flatMap { it.nodeIds }
        tutorialRequire(members.toSet() == nodes && members.distinct().size == members.size, "INVALID_CLUSTER", "graph.clusters", "分组需完整、互斥地覆盖图节点")
    }
    graph.edges.forEachIndexed { index, edge ->
        tutorialRequire(edge.from in nodes && edge.to in nodes, "INVALID_EDGE", "graph.edges[$index]", "连线端点不存在")
    }
}

internal fun TutorialGraph.sourceIds(): List<String> = nodes.mapNotNull { it.sourceId } + evidence.flatMap { listOf(it.sourceId) + it.dependencySourceIds }

internal fun validateSimulation(simulation: TutorialSimulation) {
    uniqueIds(listOf(simulation.id), "simulation.id")
    uniqueIds(simulation.scenarios.map { it.id }, "simulation.scenarios")
    tutorialRequire(simulation.scenarios.isNotEmpty(), "EMPTY_SCENARIOS", "simulation.scenarios", "模拟需要预设情境")
    val lines = simulation.code.count { it == '\n' } + 1
    simulation.scenarios.forEachIndexed { scenarioIndex, scenario ->
        tutorialRequire(scenario.steps.isNotEmpty(), "EMPTY_STEPS", "scenarios[$scenarioIndex].steps", "情境需要步骤")
        scenario.steps.forEachIndexed { index, step ->
            tutorialRequire(step.highlightLines.all { it in 1..lines }, "INVALID_LINE", "scenarios[$scenarioIndex].steps[$index].highlightLines", "高亮行超出代码范围 1..$lines")
        }
    }
}

/** 每次退步均由前缀重建输出，没有追加式播放的残留状态。 */
class SimulationPlayback(var resource: TutorialSimulation) {
    var scenarioIndex = 0
        private set
    var stepIndex = -1
        private set
    val scenario get() = resource.scenarios[scenarioIndex]
    val step get() = scenario.steps.getOrNull(stepIndex)
    val output get() = scenario.steps.take(stepIndex + 1).joinToString("") { it.stdout }
    val hasTerminal get() = resource.scenarios.any { item -> item.steps.any { it.stdout.isNotEmpty() } }
    fun next() { stepIndex = (stepIndex + 1).coerceAtMost(scenario.steps.lastIndex) }
    fun previous() { stepIndex = (stepIndex - 1).coerceAtLeast(-1) }
    fun reset() { stepIndex = -1 }
    fun select(index: Int) { require(index in resource.scenarios.indices); scenarioIndex = index; reset() }
    fun update(value: TutorialSimulation) { resource = value; scenarioIndex = 0; reset() }
}

/** 不做模糊替换；后一个 edit 基于前一个 edit 的结果。 */
internal fun editTutorialText(markdown: String, edits: List<TutorialEdit>): String = edits.fold(markdown) { text, edit ->
    val first = text.indexOf(edit.find)
    tutorialRequire(edit.find.isNotEmpty() && first >= 0, "TEXT_NOT_FOUND", "edits.find", "替换原文不存在")
    tutorialRequire(text.indexOf(edit.find, first + 1) < 0, "AMBIGUOUS_TEXT", "edits.find", "替换原文不唯一，请增加上下文")
    text.replaceRange(first, first + edit.find.length, edit.replace)
}

/** UTF-16 范围与 IDE Document 一致。交叠改写不猜测新锚点，保留原文快照。 */
internal fun relocateAnnotation(annotation: TutorialAnnotation, offset: Int, removed: Int, inserted: Int): TutorialAnnotation {
    if (annotation.orphaned) return annotation
    val end = offset + removed
    return when {
        end <= annotation.start -> annotation.copy(start = annotation.start + inserted - removed, end = annotation.end + inserted - removed)
        offset >= annotation.end -> annotation
        offset <= annotation.start && end >= annotation.end && removed > 0 -> annotation.copy(orphaned = true)
        else -> annotation.copy(start = minOf(annotation.start, offset), end = (annotation.end + inserted - removed).coerceAtLeast(minOf(annotation.start, offset)))
    }
}
