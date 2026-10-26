package com.lessplain.tutorial

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.lessplain.model.SourceAnchor
import com.lessplain.storage.sha256
import java.nio.file.Path

/** 只负责精确定位和编辑跟踪，不安装 highlighter、hover 或 gutter。 */
internal class TutorialSources(private val project: Project, private val store: TutorialStore) : AutoCloseable {
    private val markers = mutableMapOf<String, RangeMarker>()

    fun resolve(path: String, context: String, focus: String): TutorialSource {
        val focuses = occurrences(context, focus)
        tutorialRequire(focus.isNotEmpty() && focuses.size == 1, "AMBIGUOUS_FOCUS", "focus", "关注文本必须在上下文内唯一出现，请收窄上下文")
        return capture(path, context, focuses.single(), focuses.single() + focus.length)
    }

    /** IDE 选区已给出准确范围，同一上下文内重复词也能明确区分；Agent 接口不接受这些偏移。 */
    fun capture(path: String, context: String, start: Int, end: Int): TutorialSource = ApplicationManager.getApplication().runReadAction<TutorialSource> {
        val file = file(path) ?: throw TutorialProblem("SOURCE_NOT_FOUND", "path", "项目内文件不存在")
        val document = FileDocumentManager.getInstance().getDocument(file) ?: throw TutorialProblem("SOURCE_NOT_FOUND", "path", "无法读取源码文档")
        val matches = occurrences(document.text, context)
        tutorialRequire(context.isNotBlank() && matches.size == 1, if (matches.isEmpty()) "TEXT_NOT_FOUND" else "AMBIGUOUS_SOURCE", "context",
            "定位上下文需唯一匹配，候选行：${matches.map { document.getLineNumber(it) + 1 }}")
        tutorialRequire(start >= 0 && end > start && end <= context.length, "INVALID_RANGE", "focus", "选区超出定位上下文")
        val source = store.saveSource(TutorialSource(newId("src"), path.replace('\\', '/'), context, context.substring(start, end), start, end, sha256(document.text)))
        track(source)
        source
    }

    fun anchor(id: String): SourceAnchor {
        val source = store.source(id)
        return SourceAnchor(source.path, null, source.context, source.version)
    }

    fun track(source: TutorialSource): RangeMarker? {
        markers[source.id]?.let { return it.takeIf(RangeMarker::isValid) }
        val file = file(source.path) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val matches = occurrences(document.text, source.context)
        if (matches.size != 1) return null
        val offset = matches.single()
        if (source.start < 0 || source.end > source.context.length || source.context.substring(source.start, source.end) != source.focus) return null
        return document.createRangeMarker(offset + source.start, offset + source.end).also { markers[source.id] = it }
    }

    fun reveal(id: String) {
        val source = store.source(id)
        val located = ApplicationManager.getApplication().runReadAction<Pair<com.intellij.openapi.vfs.VirtualFile, IntRange>?> {
            val file = file(source.path) ?: return@runReadAction null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadAction null
            val marker = track(source) ?: return@runReadAction null
            if (!marker.isValid || marker.endOffset > document.textLength || document.getText(com.intellij.openapi.util.TextRange(marker.startOffset, marker.endOffset)) != source.focus) return@runReadAction null
            file to (marker.startOffset until marker.endOffset)
        } ?: throw TutorialProblem("SOURCE_STALE", "id", "原关注范围已变化或不唯一；材料和批注仍保留")
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, located.first, located.second.first), true)
            ?: throw TutorialProblem("SOURCE_NOT_FOUND", "id", "无法打开源码编辑器")
        editor.selectionModel.setSelection(located.second.first, located.second.last + 1)
        editor.caretModel.moveToOffset(located.second.last + 1)
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
    }

    private fun file(path: String): com.intellij.openapi.vfs.VirtualFile? {
        val base = project.basePath ?: return null
        val root = Path.of(base).toAbsolutePath().normalize()
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root)) return null
        val realTarget = try { target.toRealPath().takeIf { it.startsWith(root.toRealPath()) } }
            catch (_: java.io.IOException) { null } ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(realTarget)
    }

    override fun close() { markers.values.forEach(RangeMarker::dispose); markers.clear() }
}

internal fun occurrences(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val result = mutableListOf<Int>()
    var start = text.indexOf(query)
    while (start >= 0) { result += start; start = text.indexOf(query, start + 1) }
    return result
}
