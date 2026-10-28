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
import com.lessplain.storage.sha256
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/** 所有 UI 写入经 EDT；磁盘写入由 store 串行异步完成。正文更新不会重建编辑器。 */
class TutorialService(internal val project: Project) : Disposable {
    private var disposed = false
    val store = TutorialStore(PathManager.getConfigDir().resolve("lessplain/tutorials").resolve(sha256(project.locationHash)), cleanupFailed = { _, failure ->
        SwingUtilities.invokeLater {
            if (!disposed && !project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("code.comprehension.results")
                .createNotification("已删除教程的文件清理失败", "下次打开项目将继续清理：${failure.message}", NotificationType.ERROR).notify(project)
        }
    })
    internal val sources = TutorialSources(project, store)
    private val documents = mutableMapOf<String, Document>()
    private val views = mutableMapOf<String, MutableSet<AutoCloseable>>()
    private val files = mutableMapOf<String, TutorialVirtualFile>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val confirmedWrites = com.lessplain.core.ConfirmedWrites { action ->
        ApplicationManager.getApplication().invokeLater { if (!disposed && !project.isDisposed) action() }
    }
    private val deletion = com.lessplain.ide.MaterialDeletionConfirmation(project, ::deleteConfirmed)
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
        if (!disposed && !project.isDisposed) SwingUtilities.invokeLater { if (!disposed && !project.isDisposed) listeners.forEach { it(id) } }
    }

    fun document(id: String): Document {
        tutorialRequire(!store.isUnavailable(id), "DELETED", "id", "教程已删除或正在删除")
        return documents.getOrPut(id) {
        EditorFactory.getInstance().createDocument(store.record(id).markdown).also { doc ->
            doc.setReadOnly(store.readOnly)
            doc.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (store.isUnavailable(id)) return
                    val record = store.record(id)
                    store.change(id, record.revision, doc.text, event.offset, event.oldLength, event.newLength)
                    changed(id)
                }
            }, this)
        }
    }

    }

    fun create(contextId: String, title: String, markdown: String, resources: TutorialResourceState, receiptKey: String? = null): TutorialRecord {
        if (store.creation(contextId, title, markdown, resources) == null) {
            Regex("lessplain://source/([^\\s)]+)").findAll(markdown).forEach { store.source(it.groupValues[1]) }
            (resources.diagrams.flatMap { it.graph.sourceIds() } + store.context(contextId).sourceIds).forEach(store::source)
        }
        val record = store.create(contextId, title, markdown, resources, receiptKey)
        changed(record.id)
        return record
    }

    fun edit(id: String, expectedRevision: Int, edits: List<TutorialEdit>): TutorialRecord = edt {
        store.record(id)
        val key = sha256(com.google.gson.Gson().toJson(listOf(id, expectedRevision, edits)))
        store.receipt(key)?.let { return@edt it }
        val record = store.record(id)
        if (record.revision != expectedRevision) throw TutorialProblem("REVISION_CONFLICT", "expectedRevision", "正文已更新", record.revision)
        val next = editTutorialText(record.markdown, edits)
        validateTutorial(next, store.resource(id))
        Regex("lessplain://source/([^\\s)]+)").findAll(next).forEach { store.source(it.groupValues[1]) }
        // 使用一个命令修改同一份 Document，原生撤销与 RangeMarker 跟踪保持有效。
        store.edit(id, key) {
            WriteCommandAction.runWriteCommandAction(project, "修改教程", null, Runnable {
                val doc = document(id)
                try {
                    edits.forEach { edit ->
                        val offset = doc.text.indexOf(edit.find)
                        doc.replaceString(offset, offset + edit.find.length, edit.replace)
                    }
                } catch (failure: Throwable) { doc.setText(record.markdown); throw failure }
            })
        }
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

    fun copyContext(context: TutorialContext) {
        CopyPasteManager.getInstance().setContents(StringSelection("${context.question}\n\n请调用 lessplain_context_read({\"contextId\":\"${context.id}\"}) 读取冻结来源，使用新的结构化 Lessplain 工具保存结果。"))
    }

    fun open(id: String) = edt {
        tutorialRequire(!store.isUnavailable(id), "DELETED", "id", "教程已删除或正在删除")
        val record = store.record(id)
        FileEditorManager.getInstance(project).openFile(files.getOrPut(id) { TutorialVirtualFile(id, record.title) }, true)
    }

    fun annotationDraft(id: String, start: Int, end: Int, kind: String, parentId: String? = null): TutorialAnnotation {
        val record = store.record(id)
        require(start in 0..end && end <= record.markdown.length)
        return TutorialAnnotation(newId(if (kind == "note") "note" else "q"), id, kind, start, end, record.markdown.substring(start, end), parentId = parentId)
    }

    fun submitQuestion(id: String): TutorialAnnotation = synchronized(store) {
        val annotation = store.annotation(id)
        tutorialRequire(annotation.kind == "question", "INVALID_KIND", "id", "只有追问可以提交")
        tutorialRequire(annotation.text.isNotBlank(), "EMPTY_QUESTION", "text", "请输入问题")
        if (annotation.status != "draft") return@synchronized annotation
        val record = store.record(annotation.materialId)
        val context = store.saveContext(TutorialContext(newId("ctx"), annotation.text, record.sourceIds, materialId = record.id, selectedText = annotation.quote))
        val saved = store.updateAnnotation(id) { it.copy(status = "pending", contextId = context.id) }
        copyContext(context)
        changed(record.id)
        saved
    }

    fun answer(id: String, markdown: String, materialId: String?, receiptKey: String? = null): TutorialAnnotation {
        return store.updateAnnotation(id, receiptKey) { current ->
            tutorialRequire(current.kind == "question" && current.status != "draft", "QUESTION_NOT_SUBMITTED", "questionId", "问题尚未提交")
            current.copy(answer = markdown, answerMaterialId = materialId, status = "answered")
        }.also { changed(it.materialId) }
    }

    private val deletingAnnotations = mutableSetOf<String>()
    fun deleteAnnotation(id: String) {
        if (!deletingAnnotations.add(id)) return
        confirmedWrites.dispatch({ store.deleteAnnotation(id) }) { result ->
            deletingAnnotations.remove(id)
            result.onSuccess { changed(it.first().materialId) }.onFailure { attempt { throw it } }
        }
    }

    internal fun registerView(id: String, view: AutoCloseable): AutoCloseable {
        views.getOrPut(id) { linkedSetOf() }.add(view)
        return AutoCloseable { views[id]?.let { it.remove(view); if (it.isEmpty()) views.remove(id) } }
    }

    internal val deleteConfirming: Boolean get() = deletion.isOpen

    fun deleteTutorial(id: String) {
        if (store.readOnly || store.isUnavailable(id)) return
        val record = store.record(id)
        val resource = store.resource(id)
        deletion.show(id, record.title, "教程", "永久删除这篇教程、${store.annotations(id).size} 条批注、${resource.diagrams.size} 张插图和 ${resource.simulations.size} 个模拟？引用它的其他图和回答保留。")
    }

    internal fun deleteConfirmed(id: String) = edt {
        store.beginDeletion(id)
        documents[id]?.setReadOnly(true)
        views[id]?.toList()?.forEach(AutoCloseable::close)
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.filterIsInstance<TutorialVirtualFile>().filter { it.tutorialId == id }.forEach(manager::closeFile)
        documents.remove(id); files.remove(id); annotationDestinations.remove(id)
        changed(id)
        confirmedWrites.dispatch({ store.deleteTutorial(id) }) { result ->
            result.onFailure { failure -> attempt { throw failure } }
            changed(id)
        }
    }

    fun attempt(action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            NotificationGroupManager.getInstance().getNotificationGroup("code.comprehension.results")
                .createNotification("教程操作失败", failure.message ?: "未知错误", NotificationType.ERROR).notify(project)
        }
    }

    override fun dispose() { disposed = true; confirmedWrites.close(); deletion.close(); views.values.flatMap { it.toList() }.forEach(AutoCloseable::close); views.clear(); sources.close(); listeners.clear(); store.close() }
}

/** MCP 后台协程与编辑器共用同一个串行 UI 入口。 */
internal fun <T> edt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return com.lessplain.ide.uiRead(action)
    var result: Result<T>? = null
    ApplicationManager.getApplication().invokeAndWait { result = runCatching { com.lessplain.ide.uiRead(action) } }
    return requireNotNull(result).getOrThrow()
}
