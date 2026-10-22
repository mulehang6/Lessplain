package com.lessplain.tutorial

import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import com.lessplain.diagram.UnderstandingDiagram
import com.lessplain.diagram.createUnderstandingDiagram
import com.lessplain.diagram.LayoutDirection
import com.lessplain.ide.GraphNodePosition
import com.lessplain.model.BrowseState
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingRecord
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/** 教程只是画布的另一宿主，绘制、相机、命中和手势均由主图组件实现。 */
internal class TutorialDiagramView(
    private val service: TutorialService,
    private val tutorialId: String,
    private var resource: TutorialDiagram,
) : JPanel(BorderLayout()), AutoCloseable {
    var canvas: UnderstandingDiagram = createCanvas()
        private set
    init {
        isOpaque = false
        border = JBUI.Borders.empty(1)
        add(canvas, BorderLayout.CENTER)
    }

    private fun outline() = RoundRectangle2D.Double(0.5, 0.5, (width - 1).coerceAtLeast(0).toDouble(),
        (height - 1).coerceAtLeast(0).toDouble(), JBUI.scale(12).toDouble(), JBUI.scale(12).toDouble())

    override fun contains(x: Int, y: Int) = outline().contains(x.toDouble(), y.toDouble())
    override fun paintChildren(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try { g.clip(outline()); super.paintChildren(g) } finally { g.dispose() }
    }
    override fun paintBorder(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = JBColor.namedColor("Component.borderColor", JBColor(0xC9CCD1, 0x55585E))
            g.draw(outline())
        } finally { g.dispose() }
    }

    private fun createCanvas(): UnderstandingDiagram {
        val record = service.store.record(tutorialId)
        val graph = resource.graph.toUnderstanding { id -> service.store.source(id).let { SourceAnchor(it.path, null, it.focus, it.version) } }
        val state = service.store.reading(tutorialId).diagrams[resource.id]
        return createUnderstandingDiagram(UnderstandingRecord("$tutorialId/diagrams/${resource.id}", resource.revision, null, null,
            resource.id, "", record.contextId, graph, record.createdAt), state?.let { BrowseState(resource.id, zoom = it.zoom, panX = it.x, panY = it.y, hasPan = true) }).apply {
            preferredSize = Dimension(520, JBUI.scale(360))
            onNodePositionsChanged { positions ->
                service.store.savePositions(tutorialId, resource.id, positions.mapValues { TutorialPosition(it.value.x.toInt(), it.value.y.toInt()) })
                LayoutDirection.entries.filterNot { it == activeDirection }.forEach { setDirectionState(it, null, positions) }
            }
            val positions = service.store.resource(tutorialId).positions[resource.id].orEmpty().mapValues { GraphNodePosition(it.value.x.toDouble(), it.value.y.toDouble()) }
            LayoutDirection.entries.forEach { setDirectionState(it, null, positions) }
            onViewportChanged { saveViewport() }
            onRenameNode { id -> renameNode(id) }
            onEdgeLabelEdit { id -> renameEdge(id) }
            onClusterTitleEdit { id ->
                val cluster = resource.graph.clusters.first { it.id == id }
                Messages.showInputDialog(this, "分组名称", "编辑分组", null, cluster.label, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                    save(resource.graph.copy(clusters = resource.graph.clusters.map { if (it.id == id) it.copy(label = text) else it }))
                }
            }
            onNodeSelected { id -> resource.graph.nodes.find { it.id == id }?.sourceId?.let { source -> service.attempt { service.sources.reveal(source) } } }
        }
    }

    private fun renameNode(id: String) {
        val current = resource.graph.nodes.first { it.id == id }
        val text = Messages.showInputDialog(this, "节点名称", "重命名节点", null, current.label, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        save(resource.graph.copy(nodes = resource.graph.nodes.map { if (it.id == id) it.copy(label = text) else it }))
    }
    private fun renameEdge(id: String) {
        val edge = resource.graph.edges.first { it.id == id }
        val text = Messages.showInputDialog(this, "连线文字", "编辑连线", null, edge.label, null) ?: return
        save(resource.graph.copy(edges = resource.graph.edges.map { if (it.id == id) it.copy(label = text) else it }))
    }
    private fun save(graph: TutorialGraph) = service.attempt {
        service.store.saveDiagram(tutorialId, resource.copy(graph = graph), resource.revision)
        service.changed(tutorialId)
    }
    fun saveViewport() {
        if (!canvas.initializedViewport) return
        val reading = service.store.reading(tutorialId)
        val viewport = BrowseState(resource.id, zoom = canvas.zoom, panX = canvas.centerX, panY = canvas.centerY, hasPan = true)
        LayoutDirection.entries.forEach { canvas.directionViewports[it] = viewport }
        service.store.saveReading(tutorialId, reading.copy(diagrams = reading.diagrams + (resource.id to TutorialViewport(canvas.zoom, canvas.centerX, canvas.centerY))))
    }
    fun refresh(next: TutorialDiagram) {
        if (resource == next) return
        saveViewport(); canvas.dispose(); remove(canvas)
        resource = next
        canvas = createCanvas(); add(canvas, BorderLayout.CENTER)
        revalidate(); repaint()
    }
    override fun getPreferredSize() = Dimension(520, JBUI.scale(360))
    override fun close() { saveViewport(); canvas.dispose() }
}
