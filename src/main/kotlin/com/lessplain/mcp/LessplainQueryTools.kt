package com.lessplain.mcp

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.fileEditor.FileEditorManager
import com.lessplain.storage.recordsPage
import com.lessplain.tutorial.*
import com.intellij.mcpserver.McpToolCallResult

class LessplainQueryTools : LessplainToolsSupport() {
    @McpTool(name = "lessplain_context_capture")
    @McpDescription("冻结当前选区或图节点，返回 contextId 和插件解析的 sourceId。")
    suspend fun capture(question: String): McpToolCallResult = durableResult {
        val service = tutorial()
        val context = service.capture(question)
        mapOf("contextId" to context.id, "sourceId" to context.sourceIds.singleOrNull(), "context" to context, "sources" to context.sourceIds.map(service.store::source))
    }

    @McpTool(name = "lessplain_context_read")
    @McpDescription("读取复制载荷提供的冻结上下文与源码，普通读取没有消费副作用。")
    suspend fun contextRead(contextId: String): McpToolCallResult = durableResult {
        val tutorials = tutorial()
        val context = try { tutorials.store.context(contextId) } catch (_: TutorialProblem) { importContext(tutorials, graph().core.context(contextId)) }
        mapOf("context" to context, "sources" to context.sourceIds.map(tutorials.store::source), "graphContext" to context.graphContextToken?.let { graph().core.context(it) })
    }

    @McpTool(name = "lessplain_materials_list")
    @McpDescription("按标题或正文查找 tutorial/graph，返回摘要和分页游标。")
    suspend fun materials(kind: String, query: String = "", limit: Int = 20, cursor: String = ""): McpToolCallResult = result {
        val start = pageStart(limit, cursor)
        when (kind) {
            "tutorial" -> {
                val tutorials = tutorial()
                val items = tutorials.store.list().filter { it.title.contains(query, true) || it.markdown.contains(query, true) }
                    .sortedWith(compareByDescending<TutorialRecord> { it.createdAt }.thenByDescending { it.id })
                val selected = items.drop(start).take(limit).map {
                    mapOf("id" to it.id, "title" to it.title, "revision" to it.revision, "sources" to it.sourceIds.map(tutorials.store::source))
                }
                page(selected, items.size, start)
            }
            "graph" -> {
                val records = graph().repository.recordsPage(query, start, limit)
                page(records.items.map { mapOf("id" to it.id, "title" to (it.displayTitle ?: it.question), "revision" to it.revision, "parentId" to it.parentId) }, records.total, start)
            }
            else -> throw TutorialProblem("INVALID_KIND", "kind", "kind 为 tutorial 或 graph")
        }
    }

    @McpTool(name = "lessplain_item_read")
    @McpDescription("读取教程、图、批注或 tutorialId/diagrams/id、tutorialId/simulations/id 资源及其当前版本。")
    suspend fun item(id: String): McpToolCallResult = durableResult {
        val tutorials = tutorial()
        when {
            id.contains("/diagrams/") -> tutorials.store.resource(id.substringBefore('/')).diagrams.find { it.id == id.substringAfterLast('/') } ?: throw TutorialProblem("NOT_FOUND", "id", "插图不存在")
            id.contains("/simulations/") -> tutorials.store.resource(id.substringBefore('/')).simulations.find { it.id == id.substringAfterLast('/') } ?: throw TutorialProblem("NOT_FOUND", "id", "模拟不存在")
            id.startsWith("tut_") -> mapOf("tutorial" to tutorials.store.record(id), "annotations" to tutorials.store.annotations(id).map { mapOf("id" to it.id, "kind" to it.kind, "status" to it.status, "orphaned" to it.orphaned) },
                "resources" to mapOf("diagrams" to tutorials.store.resource(id).diagrams.map { mapOf("id" to "${id}/diagrams/${it.id}", "revision" to it.revision) },
                    "simulations" to tutorials.store.resource(id).simulations.map { mapOf("id" to "${id}/simulations/${it.id}", "revision" to it.revision) }))
            id.startsWith("note_") || id.startsWith("q_") -> tutorials.store.annotation(id)
            id.startsWith("src_") -> tutorials.store.source(id)
            else -> {
                val graphs = graph()
                val record = runCatching { graphs.repository.getRecord(id) }.getOrNull()
                if (record != null) graphResult(tutorials, record)
                else {
                    val question = graphs.repository.getQuestion(id)
                    mapOf("question" to question, "context" to importContext(tutorials, graphs.core.context(question.contextToken), question.text), "answers" to graphs.repository.answersForQuestion(id))
                }
            }
        }
    }

    @McpTool(name = "lessplain_source_resolve")
    @McpDescription("使用项目相对路径、唯一定位上下文及关注文本解析精确源码引用。歧义返回候选行，绝不绑定第一个同名词。")
    suspend fun source(path: String, context: String, focus: String): McpToolCallResult = durableResult {
        val service = tutorial()
        val source = edt { service.sources.resolve(path, context, focus) }
        mapOf("sourceId" to source.id, "source" to source)
    }

    @McpTool(name = "lessplain_reveal")
    @McpDescription("明确打开教程、理解图、批注或精确源码范围；返回源码时完整选中对应文本。")
    suspend fun reveal(id: String): McpToolCallResult = result {
        val tutorials = tutorial()
        val project = project()
        val graphs = graph()
        val graphRecord = if (!id.startsWith("src_") && !id.startsWith("tut_") && !id.startsWith("note_") && !id.startsWith("q_")) {
            runCatching { graphs.repository.getRecord(id) }.getOrNull()
        } else null
        val graphQuestion = if (graphRecord == null && !id.startsWith("src_") && !id.startsWith("tut_") && !id.startsWith("note_") && !id.startsWith("q_")) graphs.repository.getQuestion(id) else null
        val questionContext = graphQuestion?.let { graphs.core.context(it.contextToken) }
        val questionRecord = graphQuestion?.ownerRecordId?.let(graphs.repository::getRecord)
        edt {
            when {
                id.startsWith("src_") -> tutorials.sources.reveal(id)
                id.startsWith("tut_") -> tutorials.open(id.substringBefore('/'))
                id.startsWith("note_") || id.startsWith("q_") -> {
                    val annotation = tutorials.store.annotation(id)
                    tutorials.revealAnnotation(annotation.id)
                }
                graphRecord != null -> FileEditorManager.getInstance(project).openFile(graphs.editorFile(graphRecord), true)
                questionRecord != null && questionContext?.nodeId != null -> {
                    val file = graphs.editorFile(questionRecord)
                    val editor = FileEditorManager.getInstance(project).openFile(file, true).firstOrNull()
                    if (editor != null) graphs.showNodeNotes(questionRecord, questionContext.nodeId, editor.component, java.awt.Point(20, 20))
                }
                else -> throw TutorialProblem("NOT_FOUND", "id", "此批注无法定位所属图")
            }
        }
        mapOf("id" to id, "revealed" to true)
    }
}
