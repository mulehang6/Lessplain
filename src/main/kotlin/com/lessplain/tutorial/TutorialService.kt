package com.lessplain.tutorial

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.SourceAnchor
import com.lessplain.storage.sha256
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/** 所有 UI 写入经 EDT；磁盘写入由 store 串行异步完成。正文更新不会重建编辑器。 */
class TutorialService(internal val project: Project) : Disposable {
    val store = TutorialStore(PathManager.getConfigDir().resolve("lessplain/tutorials") .resolve(sha256(project.locationHash)))
    internal val sources = TutorialSources(project, store)
    private val documents = mutableMapOf<String, Document>()
    private val files = mutableMapOf<String, TutorialVirtualFile>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val annotationDestinations = mutableMapOf<String, String>()

    fun revealAnnotation(id: String) {
        val annotation = store.annotation(id)
        annotationDestinations[annotation.materialId] = id
        open(annotation.materialId)
        changed(annotation.materialId)
    }
    internal fun takeAnnotationDestination(id: String) = annotationDestinations.remove(id)?.let { requested ->
        store.annotations(id).firstOrNull { it.id == requested }
    }

    fun onChanged(listener: (String) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    fun changed(id: String) {
        if (!project.isDisposed) SwingUtilities.invokeLater { listeners.forEach { it(id) } }
    }

    fun document(id: String): Document = documents.getOrPut(id) {
        EditorFactory.getInstance().createDocument(store.record(id).markdown).also { doc ->
            doc.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val record = store.record(id)
                    store.change(id, record.revision, doc.text, event.offset, event.oldLength, event.newLength)
                    changed(id)
                }
            }, this)
        }
    }

    fun create(contextId: String, title: String, markdown: String, resources: TutorialResourceState): TutorialRecord {
        Regex("lessplain://source/([^\\s)]+)").findAll(markdown).forEach { store.source(it.groupValues[1]) }
        (resources.diagrams.flatMap { it.graph.sourceIds() } + store.context(contextId).sourceIds).forEach(store::source)
        val record = store.create(contextId, title, markdown, resources)
        changed(record.id)
        return record
    }

    fun edit(id: String, expectedRevision: Int, edits: List<TutorialEdit>): TutorialRecord = edt {
        val key = sha256(com.google.gson.Gson().toJson(listOf(id, expectedRevision, edits)))
        store.receipt(key)?.let { return@edt it }
        val record = store.record(id)
        if (record.revision != expectedRevision) throw TutorialProblem("REVISION_CONFLICT", "expectedRevision", "正文已更新", record.revision)
        val next = editTutorialText(record.markdown, edits)
        validateTutorial(next, store.resource(id))
        Regex("lessplain://source/([^\\s)]+)").findAll(next).forEach { store.source(it.groupValues[1]) }
        // 使用一个命令修改同一份 Document，原生撤销与 RangeMarker 跟踪保持有效。
        WriteCommandAction.runWriteCommandAction(project, "修改教程", null, Runnable {
            val doc = document(id)
            var current = doc.text
            edits.forEach { edit ->
                val offset = current.indexOf(edit.find)
                doc.replaceString(offset, offset + edit.find.length, edit.replace)
                current = doc.text
            }
        })
        store.record(id).also { store.saveReceipt(key, it) }
    }

    fun capture(question: String): TutorialContext {
        val graph = project.getService(UnderstandingService::class.java)
        val selection = edt {
            val selected = FileEditorManager.getInstance(project).selectedTextEditor ?: return@edt null
            if (!selected.selectionModel.hasSelection()) return@edt null
            val anchor = com.lessplain.ide.captureEditorAnchor(project, selected)
            if (anchor != null) {
                val focus = com.lessplain.ide.captureSourceFocus(selected.document.text, anchor.path, selected.selectionModel.selectionStart, selected.selectionModel.selectionEnd, anchor.symbol)
                val source = sources.capture(anchor.path, focus.anchor.text, focus.start, focus.end)
                focus.anchor to source.id
            } else null
        }
        // 图仓库只在后台访问；EDT 仅捕获编辑器状态和安装非视觉 RangeMarker。
        val context = if (selection != null) graph.core.captureContext(anchor = selection.first) else graph.captureCurrentContext()
        val sourceIds = selection?.let { listOf(it.second) } ?: context.anchor?.let { source ->
            edt { listOf(sources.resolve(source.path, source.text, source.text).id) }
        }.orEmpty()
        return store.saveContext(TutorialContext(newId("ctx"), question, sourceIds, context.token))
    }

    fun startLearning(anchor: SourceAnchor, focusStart: Int = 0, focusEnd: Int = anchor.text.length) {
        attempt {
            val source = sources.capture(anchor.path, anchor.text, focusStart, focusEnd)
            val context = store.saveContext(TutorialContext(newId("ctx"), "请把我当成零基础读者，先用大白话说明这段代码在做什么、按什么顺序发生，再解释必要的名称。术语第一次出现时就解释，不要堆术语；按需要配图或教学模拟。", listOf(source.id)))
            copyContext(context)
        }
    }

    fun copyContext(context: TutorialContext) {
        CopyPasteManager.getInstance().setContents(StringSelection("${context.question}\n\n请调用 lessplain_context_read({\"contextId\":\"${context.id}\"}) 读取冻结来源，使用新的结构化 Lessplain 工具保存结果。"))
    }

    fun open(id: String) = edt {
        val record = store.record(id)
        FileEditorManager.getInstance(project).openFile(files.getOrPut(id) { TutorialVirtualFile(id, record.title) }, true)
    }

    fun newAnnotation(id: String, start: Int, end: Int, kind: String, parentId: String? = null): TutorialAnnotation {
        val record = store.record(id)
        require(start in 0..end && end <= record.markdown.length)
        return store.createAnnotation(TutorialAnnotation(newId(if (kind == "note") "note" else "q"), id, kind, start, end, record.markdown.substring(start, end), parentId = parentId))
            .also { changed(id) }
    }

    fun submitQuestion(id: String): TutorialAnnotation {
        val annotation = store.annotation(id)
        tutorialRequire(annotation.text.isNotBlank(), "EMPTY_QUESTION", "text", "请输入问题")
        if (annotation.status == "pending") return annotation
        val record = store.record(annotation.materialId)
        val context = store.saveContext(TutorialContext(newId("ctx"), annotation.text, record.sourceIds, materialId = record.id, selectedText = annotation.quote))
        val saved = store.updateAnnotation(id) { it.copy(status = "pending", contextId = context.id) }
        copyContext(context)
        changed(record.id)
        return saved
    }

    fun answer(id: String, markdown: String, materialId: String?): TutorialAnnotation {
        return store.updateAnnotation(id) { current ->
            tutorialRequire(current.kind == "question" && current.status != "draft", "QUESTION_NOT_SUBMITTED", "questionId", "问题尚未提交")
            current.copy(answer = markdown, answerMaterialId = materialId, status = "answered")
        }.also { changed(it.materialId) }
    }

    fun deleteAnnotation(id: String): List<TutorialAnnotation> = store.deleteAnnotation(id).also { changed(it.first().materialId) }

    fun attempt(action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            NotificationGroupManager.getInstance().getNotificationGroup("code.comprehension.results")
                .createNotification("教程操作失败", failure.message ?: "未知错误", NotificationType.ERROR).notify(project)
        }
    }

    override fun dispose() { sources.close(); listeners.clear(); store.close() }
}

/** MCP 后台协程与编辑器共用同一个串行 UI 入口。 */
internal fun <T> edt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return com.lessplain.ide.uiRead(action)
    var result: Result<T>? = null
    ApplicationManager.getApplication().invokeAndWait { result = runCatching { com.lessplain.ide.uiRead(action) } }
    return requireNotNull(result).getOrThrow()
}
