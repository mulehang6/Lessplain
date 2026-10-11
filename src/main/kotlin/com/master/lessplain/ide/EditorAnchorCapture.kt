package com.master.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.master.lessplain.model.SourceAnchor
import com.master.lessplain.storage.sha256
import java.nio.file.Path

/** 捕获当前编辑器文档，包含未保存的编辑。 */
internal fun captureEditorAnchor(project: Project): SourceAnchor? = ApplicationManager.getApplication().runReadAction<SourceAnchor?> {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runReadAction null
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@runReadAction null
    val root = Path.of(project.basePath ?: return@runReadAction null).toAbsolutePath().normalize()
    val path = Path.of(file.path).toAbsolutePath().normalize()
    if (!path.startsWith(root)) return@runReadAction null
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
    if (text.isBlank()) return@runReadAction null
    val psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    val element = psi?.findElementAt(offset.coerceAtMost((document.textLength - 1).coerceAtLeast(0)))
    val symbol = symbolPathAt(element)
    SourceAnchor(
        path = root.relativize(path).toString().replace('\\', '/'),
        symbol = symbol,
        text = text,
        version = sha256(document.text),
        lineHint = document.getLineNumber(offset) + 1,
        fingerprint = sha256(text),
    )
}
