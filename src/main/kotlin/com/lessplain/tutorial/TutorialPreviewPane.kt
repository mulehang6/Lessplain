package com.lessplain.tutorial

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.ComponentView
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledEditorKit
import javax.swing.text.TabSet
import javax.swing.text.TabStop
import javax.swing.text.View
import javax.swing.text.ViewFactory

/** 单一只读文本流提供跨段落选择，资源使用真实组件，不拦截文本命中。 */
internal class TutorialPreviewPane(private val editor: Editor, private val service: TutorialService, private val tutorialId: String) : JTextPane(), AutoCloseable {
    var model = TutorialPreviewModel("")
        private set
    private var markdown: String? = null
    private val diagrams = mutableMapOf<String, TutorialDiagramView>()
    private val simulations = mutableMapOf<String, TutorialSimulationView>()
    private val slots = mutableMapOf<String, ResourceSlot>()
    var rebuilding = false
        private set
    private var fontKey = ""
    private val tracking = TutorialPreviewTracking(editor.document) {
        PreviewPosition(model.sourceAt(caretPosition), model.selection(selectionStart, selectionEnd), topSource())
    }

    init {
        isEditable = false
        margin = JBUI.insets(18, 24)
        editorKit = object : StyledEditorKit() {
            override fun getViewFactory(): ViewFactory {
                val standard = super.getViewFactory()
                return ViewFactory { element ->
                    if (element.name == StyleConstants.ComponentElementName) object : ComponentView(element) {
                        override fun getMaximumSpan(axis: Int): Float = if (axis == View.X_AXIS) Float.MAX_VALUE else super.getMaximumSpan(axis)
                        override fun getMinimumSpan(axis: Int): Float = if (axis == View.X_AXIS) 0f else super.getMinimumSpan(axis)
                    } else standard.create(element)
                }
            }
        }
    }
    override fun getScrollableTracksViewportWidth() = true

    fun refresh(): Int {
        val next = service.store.record(tutorialId).markdown
        val scheme = editor.colorsScheme
        val key = "${UIUtil.getLabelFont()}:${scheme.editorFontName}:${scheme.editorFontSize}:${scheme.defaultForeground.rgb}:${scheme.defaultBackground.rgb}"
        val reading = tracking.take()
        val resources = service.store.resource(tutorialId)
        val available = (resources.diagrams.map { "diagram/${it.id}" } + resources.simulations.map { "simulation/${it.id}" }).toSet()
        val referenced = model.parts.map { "${it.block.kind}/${it.block.resourceId}" }.toSet()
        val installed = slots.keys.map { it.substringBefore('#') }.toSet()
        if (next != markdown || key != fontKey || available.intersect(referenced) != installed) {
            rebuilding = true
            try { rebuild(next, key, reading) } finally { rebuilding = false }
        }
        resources.diagrams.forEach { value -> diagrams.filterKeys { it.substringBefore('#') == "diagram/${value.id}" }.values.forEach { it.refresh(value) } }
        resources.simulations.forEach { value -> simulations.filterKeys { it.substringBefore('#') == "simulation/${value.id}" }.values.forEach { it.refresh(value) } }
        highlighter.removeAllHighlights()
        val annotations = service.store.annotations(tutorialId).filterNot { it.orphaned }
        annotationInkRuns(annotations).forEach { run ->
            val a = model.visibleAt(run.start); val b = model.visibleAt(run.end)
            if (b > a) highlighter.addHighlight(a, b, TutorialRangePainter { g, region -> paintInk(g, region, run.kind, run.seed) })
        }
        revalidate(); repaint()
        return reading.top
    }

    private fun rebuild(next: String, key: String, reading: PreviewPosition) {
        val selection = reading.selection
        val oldCaret = reading.caret
        model = TutorialPreviewModel(next); markdown = next; fontKey = key
        val occurrences = mutableMapOf<String, Int>()
        val resourceKeys = model.parts.filter { it.block.kind in setOf("diagram", "simulation") }.associateWith {
            val id = "${it.block.kind}/${it.block.resourceId}"
            "$id#${occurrences.merge(id, 1, Int::plus)}"
        }
        slots.keys.filterNot { it in resourceKeys.values }.toList().forEach { slots.remove(it) }
        diagrams.keys.filterNot { it in resourceKeys.values }.toList().forEach { diagrams.remove(it)?.close() }
        simulations.keys.filterNot { it in resourceKeys.values }.toList().forEach { simulations.remove(it)?.close() }
        text = model.text
        val ui = UIUtil.getLabelFont()
        val scale = editor.colorsScheme.editorFontSize.toFloat() / com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontSize.coerceAtLeast(1)
        font = ui.deriveFont(ui.size2D * scale)
        foreground = editor.colorsScheme.defaultForeground
        background = editor.colorsScheme.defaultBackground
        val body = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, font.family); StyleConstants.setFontSize(this, font.size)
            StyleConstants.setForeground(this, foreground)
        }
        styledDocument.setCharacterAttributes(0, document.length, body, true)
        styledDocument.setParagraphAttributes(0, document.length, SimpleAttributeSet().apply {
            StyleConstants.setLineSpacing(this, 0.22f)
            StyleConstants.setSpaceAbove(this, 4f)
            StyleConstants.setSpaceBelow(this, 4f)
            StyleConstants.setTabSet(this, TabSet((1..12).map { TabStop(JBUI.scale(180).toFloat() * it) }.toTypedArray()))
        }, true)
        model.parts.forEach { part ->
            val block = part.block
            val attrs = SimpleAttributeSet()
            when {
                block.kind.startsWith("heading") -> { StyleConstants.setBold(attrs, true); StyleConstants.setFontSize(attrs, font.size + 6) }
                block.kind == "code" -> {
                    StyleConstants.setFontFamily(attrs, editor.colorsScheme.getFont(EditorFontType.PLAIN).family)
                    StyleConstants.setFontSize(attrs, editor.colorsScheme.editorFontSize)
                }
                block.kind in setOf("diagram", "simulation") -> {
                    val occurrence = resourceKeys.getValue(part)
                    val child = resource(block, occurrence) ?: return@forEach
                    val slot = slots.getOrPut(occurrence) { ResourceSlot(child) }
                    StyleConstants.setComponent(attrs, slot)
                }
            }
            styledDocument.setCharacterAttributes(part.from, part.to - part.from, attrs, false)
            part.mapped.styles.forEach { style ->
                styledDocument.setCharacterAttributes(part.from + style.start, style.end - style.start, SimpleAttributeSet().apply {
                    when (style.kind) {
                        "strong" -> StyleConstants.setBold(this, true)
                        "emphasis" -> StyleConstants.setItalic(this, true)
                        "code" -> { StyleConstants.setFontFamily(this, editor.colorsScheme.editorFontName); StyleConstants.setFontSize(this, editor.colorsScheme.editorFontSize) }
                        "link" -> { StyleConstants.setUnderline(this, true); StyleConstants.setForeground(this, UIUtil.getListSelectionBackground(true)) }
                    }
                }, false)
            }
            if (block.kind == "code") styleTutorialCode(styledDocument, part.from, part.mapped.text, block.language, editor)
        }
        caretPosition = model.visibleAt(oldCaret).coerceAtMost(document.length)
        if (selection != null) select(model.visibleAt(selection.first), model.visibleAt(selection.last + 1))
    }

    private fun resource(block: MarkdownContent, occurrence: String): JComponent? {
        val state = service.store.resource(tutorialId)
        return when (block.kind) {
            "diagram" -> state.diagrams.find { it.id == block.resourceId }?.let { value -> diagrams.getOrPut(occurrence) { TutorialDiagramView(service, tutorialId, value) } }
            "simulation" -> state.simulations.find { it.id == block.resourceId }?.let { value -> simulations.getOrPut(occurrence) { TutorialSimulationView(editor, SimulationPlayback(value)) } }
            else -> null
        }
    }
    fun sourceAt(point: Point) = model.sourceAt(viewToModel2D(point))
    /** viewToModel 会把行末空白夹到最近字符；悬停入口只接受真正的文字矩形。 */
    fun hoverSourceAt(point: Point): Int? {
        val offset = viewToModel2D(point)
        if (offset !in 0 until document.length) return null
        val from = modelToView2D(offset)?.bounds ?: return null
        val to = modelToView2D(offset + 1)?.bounds ?: return null
        val width = if (to.y == from.y) to.x - from.x else getFontMetrics(font).charWidth(document.getText(offset, 1)[0])
        return sourceAt(point).takeIf { java.awt.Rectangle(from.x, from.y, width, from.height).contains(point) }
    }
    fun selectedSource() = model.selection(selectionStart, selectionEnd)
    fun followSource(source: Int) {
        val viewport = parent as? javax.swing.JViewport ?: return
        val bounds = modelToView2D(model.visibleAt(source))?.bounds ?: return
        viewport.viewPosition = Point(viewport.viewPosition.x, bounds.y.coerceIn(0, (height - viewport.extentSize.height).coerceAtLeast(0)))
    }
    fun topSource(): Int = model.sourceAt(viewToModel2D(visibleRect.location))
    /** 链接与批注共用真实文字命中，行末空白不能触发链接。 */
    fun linkAt(point: Point): String? {
        if (selectionStart != selectionEnd || hoverSourceAt(point) == null) return null
        val position = viewToModel2D(point)
        val part = model.parts.firstOrNull { position in it.from until it.to } ?: return null
        return part.mapped.styles.firstOrNull { it.kind == "link" && position - part.from in it.start until it.end }?.href
    }
    fun openLink(point: Point) {
        val href = linkAt(point) ?: return
        if (href.startsWith("lessplain://source/")) service.attempt { service.sources.reveal(href.substringAfterLast('/')) }
        else com.intellij.ide.BrowserUtil.browse(href)
    }
    override fun close() { tracking.close(); diagrams.values.forEach { it.close() }; diagrams.clear(); simulations.values.forEach { it.close() }; simulations.clear(); slots.clear() }

    private inner class ResourceSlot(private val child: JComponent) : JPanel(java.awt.BorderLayout()) {
        init {
            isOpaque = false
            if (child is TutorialDiagramView) border = JBUI.Borders.empty(0, 48)
            add(child)
        }
        override fun getPreferredSize(): Dimension {
            val available = (this@TutorialPreviewPane.visibleRect.width - margin.left - margin.right).coerceAtLeast(1)
            child.setSize((available - insets.left - insets.right).coerceAtLeast(1), child.height)
            return Dimension(available, child.preferredSize.height + insets.top + insets.bottom)
        }
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        override fun getMinimumSize() = Dimension(0, preferredSize.height)
    }
}
