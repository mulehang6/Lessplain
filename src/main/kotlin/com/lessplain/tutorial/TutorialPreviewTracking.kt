package com.lessplain.tutorial

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener

internal data class PreviewPosition(val caret: Int, val selection: IntRange?, val top: Int)

/** 在首个文档修改之前冻结预览锚点，让平台范围标记跟踪一批修改，刷新后统一释放。 */
internal class TutorialPreviewTracking(private val document: Document, private val position: () -> PreviewPosition) : AutoCloseable {
    private class Pending(var caret: RangeMarker, val selection: RangeMarker?, var top: RangeMarker) : AutoCloseable {
        override fun close() { caret.dispose(); selection?.dispose(); top.dispose() }
    }
    private var pending: Pending? = null
    private val lifetime = com.intellij.openapi.util.Disposer.newDisposable("tutorial preview source tracking")
    private val listener = object : DocumentListener {
        override fun beforeDocumentChange(event: DocumentEvent) {
            if (pending != null) return
            val old = position()
            pending = Pending(point(old.caret), old.selection?.let {
                document.createRangeMarker(it.first.coerceIn(0, document.textLength), (it.last + 1).coerceIn(0, document.textLength))
            }, point(old.top))
        }
        override fun documentChanged(event: DocumentEvent) {
            pending?.let { value ->
                if (!value.caret.isValid) { value.caret.dispose(); value.caret = point(event.offset) }
                if (!value.top.isValid) { value.top.dispose(); value.top = point(event.offset) }
            }
        }
    }
    init { document.addDocumentListener(listener, lifetime) }
    private fun point(offset: Int) = offset.coerceIn(0, document.textLength).let { document.createRangeMarker(it, it) }
    fun take(): PreviewPosition {
        val value = pending ?: return position()
        pending = null
        return try {
            PreviewPosition(value.caret.startOffset, value.selection?.takeIf { it.isValid && it.endOffset > it.startOffset }
                ?.let { it.startOffset until it.endOffset }, value.top.startOffset)
        } finally { value.close() }
    }
    override fun close() { com.intellij.openapi.util.Disposer.dispose(lifetime); pending?.close(); pending = null }
}
