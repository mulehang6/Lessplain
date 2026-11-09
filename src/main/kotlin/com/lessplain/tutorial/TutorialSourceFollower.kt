package com.lessplain.tutorial

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.lessplain.ide.SourceSweepRenderer

/** 仅在用户进入或推进讲解时定位；后台刷新和手动浏览编辑器不会触发拉回。 */
internal class TutorialSourceFollower(private val sources: TutorialSources, private val invalidated: () -> Unit = {}) : AutoCloseable {
    private var editor: Editor? = null
    internal var highlight: RangeHighlighter? = null
        private set
    private var listener: com.intellij.openapi.editor.event.DocumentListener? = null
    private var listenerLifetime: com.intellij.openapi.Disposable? = null

    fun show(sourceId: String?) {
        clear()
        if (sourceId == null) return
        val (target, range) = sources.follow(sourceId)
        editor = target
        highlight = target.markupModel.addRangeHighlighter(range.first, range.last + 1,
            HighlighterLayer.SELECTION - 1, null, HighlighterTargetArea.EXACT_RANGE).apply {
            customRenderer = SourceSweepRenderer { 1f }
        }
        val expected = target.document.getText(com.intellij.openapi.util.TextRange(range.first, range.last + 1))
        listener = object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                val mark = highlight ?: return
                if (!mark.isValid || mark.endOffset > event.document.textLength ||
                    event.document.getText(com.intellij.openapi.util.TextRange(mark.startOffset, mark.endOffset)) != expected) {
                    clear()
                    invalidated()
                }
            }
        }.also {
            val lifetime = com.intellij.openapi.util.Disposer.newDisposable("Lessplain step source")
            listenerLifetime = lifetime
            target.document.addDocumentListener(it, lifetime)
        }
        target.scrollingModel.scrollTo(target.offsetToLogicalPosition(range.first), ScrollType.MAKE_VISIBLE)
    }

    fun clear() {
        val old = highlight
        val target = editor
        listenerLifetime?.let(com.intellij.openapi.util.Disposer::dispose)
        listenerLifetime = null
        listener = null
        highlight = null
        editor = null
        if (old != null && target != null && !target.isDisposed) target.markupModel.removeHighlighter(old)
    }

    override fun close() = clear()
}
