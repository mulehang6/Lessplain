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
    private var fontKey = ""

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

    fun refresh() {
        val next = service.store.record(tutorialId).markdown
        val scheme = editor.colorsScheme
        val key = "${UIUtil.getLabelFont()}:${scheme.editorFontName}:${scheme.editorFontSize}:${scheme.defaultForeground.rgb}:${scheme.defaultBackground.rgb}"
        if (next != markdown || key != fontKey) rebuild(next, key)
        val resources = service.store.resource(tutorialId)
        resources.diagrams.forEach { diagrams[it.id]?.refresh(it) }
        resources.simulations.forEach { simulations[it.id]?.refresh(it) }
        highlighter.removeAllHighlights()
        val annotations = service.store.annotations(tutorialId).filterNot { it.orphaned }
        annotationInkRuns(annotations).forEach { run ->
            val a = model.visibleAt(run.start); val b = model.visibleAt(run.end)
            if (b > a) highlighter.addHighlight(a, b, TutorialRangePainter { g, region -> paintInk(g, region, run.kind, run.seed) })
        }
        revalidate(); repaint()
    }

    private fun rebuild(next: String, key: String) {
        val selection = model.selection(selectionStart, selectionEnd)
        val oldCaret = model.sourceAt(caretPosition)
        model = TutorialPreviewModel(next); markdown = next; fontKey = key
        val resourceKeys = model.parts.filter { it.block.kind in setOf("diagram", "simulation") }.map { "${it.block.kind}/${it.block.resourceId}" }.toSet()
        slots.keys.filterNot { it in resourceKeys }.toList().forEach { slots.remove(it) }
        diagrams.keys.filterNot { "diagram/$it" in resourceKeys }.toList().forEach { diagrams.remove(it)?.close() }
        simulations.keys.filterNot { "simulation/$it" in resourceKeys }.toList().forEach { simulations.remove(it) }
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
                    val child = resource(block) ?: return@forEach
                    val slot = slots.getOrPut("${block.kind}/${block.resourceId}") { ResourceSlot(child) }
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

    private fun resource(block: MarkdownContent): JComponent? {
        val state = service.store.resource(tutorialId)
        return when (block.kind) {
            "diagram" -> state.diagrams.find { it.id == block.resourceId }?.let { value -> diagrams.getOrPut(value.id) { TutorialDiagramView(service, tutorialId, value) } }
            "simulation" -> state.simulations.find { it.id == block.resourceId }?.let { value -> simulations.getOrPut(value.id) { TutorialSimulationView(editor, SimulationPlayback(value)) } }
            else -> null
        }
    }
    fun sourceAt(point: Point) = model.sourceAt(viewToModel2D(point))
    fun selectedSource() = model.selection(selectionStart, selectionEnd)
    fun followSource(source: Int) {
        val viewport = parent as? javax.swing.JViewport ?: return
        val bounds = modelToView2D(model.visibleAt(source))?.bounds ?: return
        viewport.viewPosition = Point(viewport.viewPosition.x, bounds.y.coerceIn(0, (height - viewport.extentSize.height).coerceAtLeast(0)))
    }
    fun topSource(): Int = model.sourceAt(viewToModel2D(visibleRect.location))
    fun openLink(point: Point) {
        if (selectionStart != selectionEnd) return
        val position = viewToModel2D(point)
        val part = model.parts.firstOrNull { position in it.from until it.to } ?: return
        val href = part.mapped.styles.firstOrNull { it.kind == "link" && position - part.from in it.start until it.end }?.href ?: return
        if (href.startsWith("lessplain://source/")) service.attempt { service.sources.reveal(href.substringAfterLast('/')) }
        else com.intellij.ide.BrowserUtil.browse(href)
    }
    override fun close() { diagrams.values.forEach { it.close() }; diagrams.clear(); simulations.clear(); slots.clear() }

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
