package com.master.understanding.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Timer

/** Owns the editor listeners and reveal timer for one source marker. */
internal class EditorSourceInsightMarker(
    private val editor: Editor,
    start: Int,
    end: Int,
    label: String,
    private val navigation: SourceInlayNavigation,
) : Disposable {
    private val renderer = InsightInlayRenderer(label, start, end, navigation.canPrevious, navigation.canNext)
    private val lineStart = editor.document.getLineStartOffset(editor.document.getLineNumber(start))
    private val inlay = editor.inlayModel.addBlockElement(lineStart, false, true, -100, renderer)
    val isInstalled: Boolean get() = inlay != null
    private var reveal = 0f
    private var hoverStart = 0L
    private var hovered = false
    private val hoverState = SourceHighlightHoverState()
    private var disposed = false
    private val highlighter = inlay?.let {
        editor.markupModel.addRangeHighlighter(start, end, HighlighterLayer.ADDITIONAL_SYNTAX,
            TextAttributes(), HighlighterTargetArea.EXACT_RANGE).apply {
            customRenderer = SourceSweepRenderer { reveal }
        }
    }
    internal val timer: Timer = Timer(16) { event ->
        if (disposed) {
            (event.source as Timer).stop()
            return@Timer
        }
        reveal = ((System.nanoTime() - hoverStart) / 200_000_000f).coerceIn(0f, 1f)
        editor.contentComponent.repaint()
        if (reveal >= 1f) (event.source as Timer).stop()
    }
    private val mouse = object : MouseAdapter() {
        override fun mouseMoved(event: MouseEvent) {
            if (disposed) return
            setHovered(hoverState.move(inHint(event.point), inBlock(event.point)))
            val button = inlay?.let { renderer.buttonAt(it, event.point) }
            if (renderer.hoveredButton != button) {
                renderer.hoveredButton = button
                inlay?.update()
            }
        }

        override fun mouseExited(event: MouseEvent) {
            if (disposed) return
            hoverState.exit()
            setHovered(false)
            renderer.hoveredButton = null
            inlay?.update()
        }

        override fun mousePressed(event: MouseEvent) {
            if (disposed) return
            setHovered(hoverState.press(inHint(event.point), inBlock(event.point)))
        }

        override fun mouseDragged(event: MouseEvent) {
            if (disposed) return
            setHovered(hoverState.drag(inHint(event.point), inBlock(event.point)))
        }

        override fun mouseClicked(event: MouseEvent) {
            if (disposed) return
            if (event.button != MouseEvent.BUTTON1) return
            when (inlay?.let { renderer.buttonAt(it, event.point) }) {
                SourceInlayButton.PREVIOUS -> if (navigation.canPrevious) {
                    event.consume()
                    navigation.previous(editor.contentComponent, event.point)
                }
                SourceInlayButton.NEXT -> if (navigation.canNext) {
                    event.consume()
                    navigation.next(editor.contentComponent, event.point)
                }
                SourceInlayButton.EXPAND -> {
                    event.consume()
                    renderer.expanded = !renderer.expanded
                    inlay.update()
                }
                null -> Unit
            }
        }
    }
    private val resize = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) { if (!disposed) inlay?.update() }
    }

    init {
        if (inlay != null) editor.contentComponent.addMouseMotionListener(mouse)
        if (inlay != null) editor.contentComponent.addMouseListener(mouse)
        if (inlay != null) editor.contentComponent.addComponentListener(resize)
    }

    private fun inHint(point: Point): Boolean = inlay?.let { renderer.hintBounds(it).contains(point) } == true

    private fun inBlock(point: Point): Boolean = highlighter?.let {
        sourceBlockBounds(editor, it.startOffset, it.endOffset)?.contains(point)
    } == true

    private fun setHovered(value: Boolean) {
        if (hovered == value) return
        hovered = value
        renderer.hovered = value
        inlay?.update()
        if (value) {
            reveal = 0f
            hoverStart = System.nanoTime()
            timer.start()
        } else {
            timer.stop()
            reveal = 0f
            editor.contentComponent.repaint()
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        hoverState.dispose()
        timer.stop()
        editor.contentComponent.removeMouseMotionListener(mouse)
        editor.contentComponent.removeMouseListener(mouse)
        editor.contentComponent.removeComponentListener(resize)
        inlay?.dispose()
        highlighter?.dispose()
        editor.contentComponent.repaint()
    }
}
