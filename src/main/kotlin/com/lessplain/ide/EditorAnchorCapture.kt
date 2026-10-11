package com.lessplain.ide

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.lessplain.model.SourceAnchor
import com.lessplain.storage.sha256
import java.nio.file.Path

/** 捕获当前编辑器文档，包含未保存的编辑。 */
internal fun captureEditorAnchor(project: Project): SourceAnchor? =
    FileEditorManager.getInstance(project).selectedTextEditor?.let { captureEditorAnchor(project, it) }

/** 使用动作提供的编辑器，不能替换成全局当前标签页。 */
internal fun captureEditorAnchor(project: Project, editor: Editor): SourceAnchor? =
    ApplicationManager.getApplication().runReadAction<SourceAnchor?> {
        if (editor.isDisposed || editor.project != project) return@runReadAction null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@runReadAction null
        val root = Path.of(project.basePath ?: return@runReadAction null).toAbsolutePath().normalize()
        val path = Path.of(file.path).toAbsolutePath().normalize()
        if (!path.startsWith(root)) return@runReadAction null
        val offset = (if (editor.selectionModel.hasSelection()) editor.selectionModel.selectionStart else editor.caretModel.offset)
            .coerceIn(0, editor.document.textLength)
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        val element = psi?.findElementAt(offset.coerceAtMost((editor.document.textLength - 1).coerceAtLeast(0)))
        val symbol = symbolPathAt(element)
        captureAnchorSnapshot(editor, root.relativize(path).toString().replace('\\', '/'), symbol)
    }

/** 在同一文档读取快照里捕获选区及全文版本，未保存内容也参与版本计算。 */
internal fun captureAnchorSnapshot(editor: Editor, path: String, symbol: String?): SourceAnchor? {
    val document = editor.document
    val selection = editor.selectionModel
    val offset = (if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset)
        .coerceIn(0, document.textLength)
    val text = if (selection.hasSelection()) {
        selection.selectedText.orEmpty()
    } else {
        val line = document.getLineNumber(offset)
        document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    }
    if (text.isBlank()) return null

    return SourceAnchor(
        path = path,
        symbol = symbol,
        text = text,
        version = sha256(document.text),
        lineHint = document.getLineNumber(offset) + 1,
        fingerprint = sha256(text),
    )
}
