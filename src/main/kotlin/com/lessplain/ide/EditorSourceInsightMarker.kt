package com.lessplain.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** 上方块提示按优先级升序排列，最高优先级使说明紧贴对应代码。 */
private const val SOURCE_INLAY_PRIORITY = Int.MAX_VALUE

/** 管理单个源码提示的编辑器监听器和渐显计时器。 */
internal class EditorSourceInsightMarker(
    private val editor: Editor,
    start: Int,
    end: Int,
    label: String,
    private val navigation: SourceInlayNavigation,
    private val binding: SourceNodeBinding? = null,
) : Disposable {
    private var highlighter: RangeHighlighter? = null
    private val renderer = InsightInlayRenderer(label, {
        highlighter?.takeIf { it.isValid }?.let { it.startOffset until it.endOffset }
    }, navigation.canPrevious, navigation.canNext)
    private val originalText = editor.document.charsSequence.subSequence(start, end).toString()
    private val lineStart = editor.document.getLineStartOffset(editor.document.getLineNumber(start))
    private val inlay = editor.inlayModel.addBlockElement(lineStart, false, true, SOURCE_INLAY_PRIORITY, renderer)
    val isInstalled: Boolean get() = inlay != null
    internal val animation = SourceSweepAnimation { editor.contentComponent.repaint() }
    private var hovered = false
    private val hoverState = SourceHighlightHoverState()
    private var disposed = false
    private val documentLifetime = Disposer.newDisposable("Understanding source choice listener")
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            val onEdt = SwingUtilities.isEventDispatchThread()
            if (onEdt) navigation.cancelChoices()
            SwingUtilities.invokeLater {
                if (!disposed) {
                    if (!onEdt) navigation.cancelChoices()
                    val range = highlighter
                    if (range == null || !range.isValid || sourceExcerptChanged(
                            editor.document.charsSequence, range.startOffset, range.endOffset, originalText,
                        )) navigation.evidenceChanged()
                }
            }
        }
    }
    init {
        highlighter = inlay?.let {
            editor.markupModel.addRangeHighlighter(
                start, end, HighlighterLayer.ADDITIONAL_SYNTAX,
                TextAttributes(), HighlighterTargetArea.EXACT_RANGE,
            ).apply {
                customRenderer = SourceSweepRenderer { animation.progress }
            }
        }
        inlay?.update()
    }
    private val mouse = object : MouseAdapter() {
        override fun mouseMoved(event: MouseEvent) {
            if (disposed) return
            setHovered(hoverState.move(inHint(event.point), inRetentionArea(event.point)))
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
            setHovered(hoverState.press(inHint(event.point), inRetentionArea(event.point)))
        }

        override fun mouseDragged(event: MouseEvent) {
            if (disposed) return
            setHovered(hoverState.drag(inHint(event.point), inRetentionArea(event.point)))
        }

        override fun mouseClicked(event: MouseEvent) {
            if (disposed) return
            if (event.button != MouseEvent.BUTTON1) return
            when (inlay?.let { renderer.buttonAt(it, event.point) }) {
                SourceInlayButton.PREVIOUS -> if (navigation.canPrevious) {
                    event.consume()
                    navigation.previous()
                }

                SourceInlayButton.NEXT -> if (navigation.canNext) {
                    event.consume()
                    navigation.next()
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
        override fun componentResized(event: ComponentEvent) {
            if (!disposed) {
                inlay?.update()
            }
        }
    }

    init {
        if (inlay != null && binding != null) editor.putUserData(SOURCE_NODE_BINDING, binding)
        if (inlay != null) editor.document.addDocumentListener(documentListener, documentLifetime)
        if (inlay != null) editor.contentComponent.addMouseMotionListener(mouse)
        if (inlay != null) editor.contentComponent.addMouseListener(mouse)
        if (inlay != null) editor.contentComponent.addComponentListener(resize)
    }

    private fun inHint(point: Point): Boolean = inlay?.let { renderer.hintBounds(it).contains(point) } == true

    /** 短间隙只延续已触发的高亮，不参与绘制或独立触发。 */
    private fun inRetentionArea(point: Point): Boolean {
        val range = highlighter?.takeIf { it.isValid } ?: return false
        val block = sourceBlockBounds(editor, range.startOffset, range.endOffset) ?: return false
        if (block.contains(point)) return true
        val hint = inlay?.takeIf { it.bounds != null }?.let { renderer.hintBounds(it) }
        return sourceHighlightHoverBridge(hint, block, JBUI.scale(4))?.contains(point) == true
    }

    private fun setHovered(value: Boolean) {
        if (hovered == value) return
        hovered = value
        renderer.hovered = value
        inlay?.update()
        if (value) {
            animation.start()
        } else {
            animation.reset()
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        if (binding != null && editor.getUserData(SOURCE_NODE_BINDING) === binding) {
            editor.putUserData(SOURCE_NODE_BINDING, null)
        }
        Disposer.dispose(documentLifetime)
        navigation.cancelChoices()
        hoverState.dispose()
        animation.close()
        editor.contentComponent.removeMouseMotionListener(mouse)
        editor.contentComponent.removeMouseListener(mouse)
        editor.contentComponent.removeComponentListener(resize)
        inlay?.dispose()
        highlighter?.dispose()
        editor.contentComponent.repaint()
    }
}
