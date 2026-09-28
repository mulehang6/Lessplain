package com.master.understanding.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.master.understanding.model.SourceAnchor
import java.awt.Color
import java.awt.Component
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import java.nio.file.Path
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Owns only the inlays and highlighters created for the active Understanding node. */
internal class SourceInsightMarker(private val project: Project, parent: Disposable) : Disposable {
    private data class Target(
        val document: Document,
        val start: Int,
        val end: Int,
        val label: String,
        val navigation: SourceInlayNavigation,
    )

    private val factory = EditorFactory.getInstance()
    private val markers = mutableMapOf<Editor, EditorMarker>()
    private var target: Target? = null
    private var disposed = false

    init {
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                SwingUtilities.invokeLater {
                    if (!disposed && factory.getAllEditors().any { it === editor }) target?.let { attach(editor, it) }
                }
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                markers.remove(event.editor)?.dispose()
            }
        }, parent)
    }

    /** Resolves the evidence without opening a source editor. */
    fun show(anchor: SourceAnchor?, label: String, navigation: SourceInlayNavigation): LocatedSource? {
        check(SwingUtilities.isEventDispatchThread())
        clear()
        if (anchor == null || disposed) return null
        val located = locateSource(project, anchor)
        val start = located.offset ?: return located
        val root = Path.of(project.basePath ?: return located).toAbsolutePath().normalize()
        val path = root.resolve(anchor.path).normalize()
        if (!path.startsWith(root)) return located
        val document = ApplicationManager.getApplication().runReadAction<Document?> {
            val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@runReadAction null
            FileDocumentManager.getInstance().getDocument(file)
        } ?: return located
        val end = (start + anchor.text.length).coerceAtMost(document.textLength)
        if (end <= start) return located
        val singleLineLabel = label.replace('\n', ' ')
        val caption = if (singleLineLabel.length > 28) singleLineLabel.take(28) + "…" else singleLineLabel
        target = Target(document, start, end, caption, navigation)
        refreshOpenEditors()
        return located
    }

    fun refreshOpenEditors() {
        check(SwingUtilities.isEventDispatchThread())
        val value = target ?: return
        factory.getEditors(value.document, project).forEach { attach(it, value) }
    }

    fun clear() {
        check(SwingUtilities.isEventDispatchThread())
        target = null
        markers.values.forEach(EditorMarker::dispose)
        markers.clear()
    }

    private fun attach(editor: Editor, value: Target) {
        if (editor.project != project || editor.document !== value.document || editor in markers) return
        EditorMarker(editor, value.start, value.end, value.label, value.navigation).takeIf { it.isInstalled }?.let {
            markers[editor] = it
        }
    }

    override fun dispose() {
        disposed = true
        if (SwingUtilities.isEventDispatchThread()) clear()
        else SwingUtilities.invokeLater { clear() }
    }
}

internal data class SourceInlayNavigation(
    val canPrevious: Boolean,
    val canNext: Boolean,
    val previous: () -> Unit,
    val next: (Component, Point) -> Unit,
)

private class EditorMarker(
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
    private val highlighter = inlay?.let {
        editor.markupModel.addRangeHighlighter(start, end, HighlighterLayer.ADDITIONAL_SYNTAX,
            TextAttributes(), HighlighterTargetArea.EXACT_RANGE).apply {
            customRenderer = SourceSweepRenderer { reveal }
        }
    }
    private val timer: Timer = Timer(16) { event ->
        reveal = ((System.nanoTime() - hoverStart) / 200_000_000f).coerceIn(0f, 1f)
        editor.contentComponent.repaint()
        if (reveal >= 1f) (event.source as Timer).stop()
    }
    private val mouse = object : MouseAdapter() {
        override fun mouseMoved(event: MouseEvent) {
            setHovered(inlay?.let { renderer.hintBounds(it).contains(event.point) } == true)
            val button = inlay?.let { renderer.buttonAt(it, event.point) }
            if (renderer.hoveredButton != button) {
                renderer.hoveredButton = button
                inlay?.update()
            }
        }

        override fun mouseExited(event: MouseEvent) {
            setHovered(false)
            renderer.hoveredButton = null
            inlay?.update()
        }

        override fun mouseClicked(event: MouseEvent) {
            if (event.button != MouseEvent.BUTTON1) return
            when (inlay?.let { renderer.buttonAt(it, event.point) }) {
                SourceInlayButton.PREVIOUS -> if (navigation.canPrevious) {
                    event.consume()
                    navigation.previous()
                }
                SourceInlayButton.NEXT -> if (navigation.canNext) {
                    event.consume()
                    navigation.next(editor.contentComponent, event.point)
                }
                null -> Unit
            }
        }
    }

    init {
        if (inlay != null) editor.contentComponent.addMouseMotionListener(mouse)
        if (inlay != null) editor.contentComponent.addMouseListener(mouse)
    }

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
        timer.stop()
        editor.contentComponent.removeMouseMotionListener(mouse)
        editor.contentComponent.removeMouseListener(mouse)
        inlay?.dispose()
        highlighter?.dispose()
        editor.contentComponent.repaint()
    }
}

private enum class SourceInlayButton { PREVIOUS, NEXT }

internal data class SourceInlayControlBounds(
    val hint: Rectangle,
    val previous: Rectangle,
    val next: Rectangle,
)

internal fun sourceInlayControlBounds(left: Int, top: Int, height: Int, textWidth: Int): SourceInlayControlBounds {
    val buttonWidth = 20
    val gap = 3
    val firstX = left + textWidth + 20
    return SourceInlayControlBounds(
        hint = Rectangle(left, top, textWidth + 20 + buttonWidth * 2 + gap, height),
        previous = Rectangle(firstX, top + 2, buttonWidth, (height - 4).coerceAtLeast(1)),
        next = Rectangle(firstX + buttonWidth + gap, top + 2, buttonWidth, (height - 4).coerceAtLeast(1)),
    )
}

private class InsightInlayRenderer(
    private val label: String,
    private val start: Int,
    private val end: Int,
    private val canPrevious: Boolean,
    private val canNext: Boolean,
) : EditorCustomElementRenderer {
    var hovered = false
    var hoveredButton: SourceInlayButton? = null

    private fun textWidth(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return editor.contentComponent.getFontMetrics(font).stringWidth("◉ Understanding · $label")
    }

    private fun left(inlay: Inlay<*>): Int = sourceBlockBounds(inlay.editor, start, end)?.x
        ?: inlay.bounds?.x ?: 0

    private fun controls(inlay: Inlay<*>): SourceInlayControlBounds {
        val bounds = inlay.bounds
        return sourceInlayControlBounds(left(inlay), bounds?.y ?: 0,
            bounds?.height ?: inlay.editor.lineHeight, textWidth(inlay))
    }

    private fun buttonBounds(inlay: Inlay<*>, button: SourceInlayButton): Rectangle =
        controls(inlay).let { if (button == SourceInlayButton.PREVIOUS) it.previous else it.next }

    fun buttonAt(inlay: Inlay<*>, point: Point): SourceInlayButton? = when {
        buttonBounds(inlay, SourceInlayButton.PREVIOUS).contains(point) -> SourceInlayButton.PREVIOUS
        buttonBounds(inlay, SourceInlayButton.NEXT).contains(point) -> SourceInlayButton.NEXT
        else -> null
    }

    fun hintBounds(inlay: Inlay<*>): Rectangle {
        return controls(inlay).hint
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        return sourceInlayControlBounds(left(inlay), 0, inlay.editor.lineHeight, textWidth(inlay)).hint.maxX.toInt()
    }

    override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
        val controls = sourceInlayControlBounds(left(inlay), targetRegion.y.toInt(),
            targetRegion.height.toInt(), textWidth(inlay))
        val bounds = controls.hint
        if (hovered) {
            g.color = Color(130, 95, 200, 30)
            g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6)
        }
        g.font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.color = if (hovered) JBColor(Color(103, 58, 183), Color(189, 145, 242))
            else JBColor(Color(79, 72, 112), Color(173, 163, 199))
        g.drawString("◉ Understanding · $label", bounds.x + 8, bounds.y + (bounds.height + g.fontMetrics.ascent) / 2 - 2)
        paintButton(g, controls.previous, SourceInlayButton.PREVIOUS, "‹", canPrevious)
        paintButton(g, controls.next, SourceInlayButton.NEXT, "›", canNext)
    }

    private fun paintButton(g: Graphics2D, b: Rectangle, button: SourceInlayButton, glyph: String, enabled: Boolean) {
        if (hoveredButton == button && enabled) {
            g.color = Color(130, 95, 200, 50)
            g.fillRoundRect(b.x, b.y, b.width, b.height, 5, 5)
        }
        g.color = if (enabled) JBColor(Color(103, 58, 183), Color(189, 145, 242))
            else JBColor(Color(166, 166, 174), Color(105, 105, 114))
        val metrics = g.fontMetrics
        g.drawString(glyph, b.x + (b.width - metrics.stringWidth(glyph)) / 2,
            b.y + (b.height + metrics.ascent) / 2 - 2)
    }

}

/** One rectangular code area, shared by the hint and the hover highlight. */
private fun sourceBlockBounds(editor: Editor, startOffset: Int, endOffset: Int): Rectangle? {
    val document = editor.document
    val start = startOffset.coerceIn(0, document.textLength)
    val end = endOffset.coerceIn(start, document.textLength)
    if (start == end) return null

    val first = document.getLineNumber(start)
    val last = document.getLineNumber(end - 1)
    val chars = document.charsSequence
    var left = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    for (line in first..last) {
        var from = maxOf(start, document.getLineStartOffset(line))
        var to = minOf(end, document.getLineEndOffset(line))
        while (from < to && chars[from].isWhitespace()) from++
        while (to > from && chars[to - 1].isWhitespace()) to--
        if (to <= from) continue
        left = minOf(left, editor.offsetToXY(from).x)
        right = maxOf(right, editor.offsetToXY(to).x)
    }
    if (left == Int.MAX_VALUE) return null
    val top = editor.offsetToXY(document.getLineStartOffset(first)).y
    val bottom = editor.offsetToXY(document.getLineStartOffset(last)).y + editor.lineHeight
    val padding = 5
    return Rectangle(left - padding, top - 2, (right - left + padding * 2).coerceAtLeast(1), bottom - top + 4)
}

/** A translucent left-to-right reveal over the resolved evidence, beneath editor text. */
private class SourceSweepRenderer(private val progress: () -> Float) : CustomHighlighterRenderer {
    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        val amount = progress()
        if (amount <= 0f) return
        val g = graphics.create() as Graphics2D
        try {
            val bounds = sourceBlockBounds(editor, highlighter.startOffset, highlighter.endOffset) ?: return
            val revealed = (bounds.width * amount).toInt().coerceAtLeast(1)
            g.clipRect(bounds.x, bounds.y, revealed, bounds.height)
            g.paint = GradientPaint(bounds.x.toFloat(), bounds.y.toFloat(), Color(128, 86, 216, 105),
                (bounds.x + bounds.width).toFloat(), bounds.y.toFloat(), Color(128, 86, 216, 28))
            g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8)
        } finally { g.dispose() }
    }
}
