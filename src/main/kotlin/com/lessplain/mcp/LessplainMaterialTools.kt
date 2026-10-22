package com.lessplain.mcp

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.lessplain.model.UNDERSTANDING_SCHEMA_REVISION
import com.lessplain.model.UnderstandingRevisionConflict
import com.lessplain.model.ClusterUpdate
import com.lessplain.storage.sha256
import com.lessplain.tutorial.*
import com.intellij.mcpserver.McpToolCallResult

class LessplainMaterialTools : LessplainToolsSupport() {
    @McpTool(name = "lessplain_graph_create")
    @McpDescription("创建理解图，省略 parentId 为根图。保留现有关系语义和证据要求；sourceId 从冻结上下文或 source_resolve 获取；不传坐标。")
    suspend fun graphCreate(contextId: String, title: String, graph: TutorialGraph, parentId: String? = null): McpToolCallResult = writeResult("graph_create", listOf(contextId, title, graph, parentId)) {
        val tutorials = tutorial(); val graphs = graph()
        val context = try { tutorials.store.context(contextId) } catch (_: TutorialProblem) { importContext(tutorials, graphs.core.context(contextId)) }
        val request = sha256(gson.toJson(listOf("graph_create", contextId, title, graph, parentId)))
        val record = graphs.core.present(UNDERSTANDING_SCHEMA_REVISION, request, context.question.ifBlank { title }, graph.toUnderstanding(tutorials.sources::anchor),
            if (parentId == null) "root" else "explicit", context.graphContextToken, parentId = parentId, displayTitle = title)
        graphs.resultSaved(recordId = record.id, questionId = record.questionId, revision = record.revision)
        graphResult(tutorials, record)
    }

    @McpTool(name = "lessplain_graph_revise")
    @McpDescription("修订理解图。保留节点与连线 ID、顺序、端点和关系类型；不改变原问题、归属或批注。")
    suspend fun graphRevise(id: String, expectedRevision: Int, graph: TutorialGraph): McpToolCallResult = writeResult("graph_revise", listOf(id, expectedRevision, graph)) {
        val tutorials = tutorial(); val graphs = graph(); val old = graphs.repository.getRecord(id)
        val value = graph.toUnderstanding(tutorials.sources::anchor)
        val record = try {
            graphs.core.revise(UNDERSTANDING_SCHEMA_REVISION, sha256(gson.toJson(listOf("graph_revise", id, expectedRevision, graph))), old.question, value, id, expectedRevision, ClusterUpdate.Replace(value.clusters))
        } catch (conflict: UnderstandingRevisionConflict) {
            throw TutorialProblem("REVISION_CONFLICT", "expectedRevision", "图已更新", conflict.currentRevision)
        }
        graphs.resultSaved(recordId = record.id, questionId = record.questionId, revision = record.revision)
        graphResult(tutorials, record)
    }

    @McpTool(name = "lessplain_tutorial_create")
    @McpDescription("原子校验并创建 Markdown 教程及全部插图/教学模拟。资源引用使用 lessplain://diagram/id、lessplain://simulation/id；源码链接使用 lessplain://source/sourceId。")
    suspend fun tutorialCreate(contextId: String, title: String, markdown: String, diagrams: List<TutorialDiagram> = emptyList(), simulations: List<TutorialSimulation> = emptyList()): McpToolCallResult = writeResult("tutorial_create", listOf(contextId, title, markdown, diagrams, simulations)) {
        val service = tutorial()
        val record = service.create(contextId, title, markdown, TutorialResourceState(diagrams, simulations))
        mapOf("tutorial" to record, "resources" to service.store.resource(record.id))
    }

    @McpTool(name = "lessplain_tutorial_edit")
    @McpDescription("按唯一原文定点修改教程。每个 find 必须恰好匹配一次；expectedRevision 仅防止并发丢写，允许修改用户编辑的正文。")
    suspend fun tutorialEdit(id: String, expectedRevision: Int, edits: List<TutorialEdit>): McpToolCallResult = writeResult("tutorial_edit", listOf(id, expectedRevision, edits)) { tutorial().edit(id, expectedRevision, edits) }

    @McpTool(name = "lessplain_diagram_save")
    @McpDescription("新增或完整更新教程插图。新增 expectedRevision=0；更新使用当前版本。按节点 ID 保留人工位置。")
    suspend fun diagramSave(tutorialId: String, id: String, expectedRevision: Int, graph: TutorialGraph): McpToolCallResult = writeResult("diagram_save", listOf(tutorialId, id, expectedRevision, graph)) {
        val service = tutorial()
        graph.sourceIds().forEach(service.store::source)
        service.store.saveDiagram(tutorialId, TutorialDiagram(id, graph), expectedRevision).also { service.changed(tutorialId) }
    }

    @McpTool(name = "lessplain_simulation_save")
    @McpDescription("新增或整体替换预设教学模拟。每步 highlightLines 从1开始，stdout 为本步新增输出。没有真实执行。更新资源后重置播放。")
    suspend fun simulationSave(tutorialId: String, id: String, expectedRevision: Int, language: String, code: String, scenarios: List<SimulationScenario>): McpToolCallResult = writeResult("simulation_save", listOf(tutorialId, id, expectedRevision, language, code, scenarios)) {
        val service = tutorial()
        service.store.saveSimulation(tutorialId, TutorialSimulation(id, language, code, scenarios), expectedRevision).also { service.changed(tutorialId) }
    }
}
