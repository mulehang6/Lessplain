package com.lessplain.diagram

import com.lessplain.model.GraphEdge
import com.lessplain.model.UnderstandingGraph
import org.eclipse.elk.graph.ElkEdge
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.max

/** 排列分组前，组内坐标为标题、节点动作和示例预留空间。 */
internal data class DiagramClusterContent(
    val cluster: PlacedCluster,
    val nodes: Map<String, PlacedNode>,
    val edges: List<PlacedEdge>,
) {
    fun translated(x: Double, y: Double): DiagramClusterContent = copy(
        cluster = cluster.copy(bounds = cluster.bounds.translated(x, y),
            exampleBounds = cluster.exampleBounds?.translated(x, y)),
        nodes = nodes.mapValues { (_, node) -> node.copy(bounds = node.bounds.translated(x, y)) },
        edges = edges.map { it.translated(x, y) },
    )
}

internal fun layoutClusterContent(graph: UnderstandingGraph, group: DiagramCluster,
    panel: DiagramExamplePanel?, direction: LayoutDirection, separateLanes: Boolean = true): DiagramClusterContent {
    val localGraph = UnderstandingGraph(graph.nodes.filter { it.id in group.nodeIds },
        graph.edges.filter { it.from in group.nodeIds && it.to in group.nodeIds })
    val input = DiagramLayoutInput.prepare(localGraph, direction)
    val layout = ElkGraphLayoutAdapter.layout(if (separateLanes) reserveDiagramPorts(input, graph) else input, separateLanes)
    val content = Rectangle2D.Double().apply {
        layout.nodes.values.firstOrNull()?.bounds?.let(::setRect)
        layout.nodes.values.drop(1).forEach { add(it.bounds) }
        layout.edges.forEach { add(it.paintBounds) }
    }
    val width = max(360.0, content.width + 56.0)
    val top = max(84.0, DiagramClusterText.measure(group.title, width).height + 12.0)
    val example = panel?.let { Rectangle2D.Double(width + 20, top,
        DiagramExamplePainter.PANEL_WIDTH.toDouble(), DiagramExamplePainter.PANEL_HEIGHT.toDouble()) }
    val box = Rectangle2D.Double(0.0, 0.0, example?.maxX?.plus(12) ?: width,
        max(content.height + top + 52, example?.maxY?.plus(12) ?: 0.0))
    val cluster = PlacedCluster(group.id, group.title, box, example, group.nodeIds,
        DiagramClusterText.measure(group.title, box.width))
    val offsetX = (width - content.width) / 2 - content.x
    val offsetY = top - content.y
    return DiagramClusterContent(cluster,
        layout.nodes.mapValues { (_, node) -> node.copy(bounds = node.bounds.translated(offsetX, offsetY)) },
        layout.edges.map { it.translated(offsetX, offsetY) })
}

/** 将 ELK 跨组通道延伸到实际节点端口，再修复局部遮挡。 */
internal fun connectClusterContent(edge: GraphEdge, elk: ElkEdge, nodes: Map<String, PlacedNode>,
    direction: LayoutDirection, ports: DiagramEdgePorts? = null): PlacedEdge {
    val corridor = ElkGraphLayoutAdapter.placedEdge(edge, elk)
    val first = elk.sections.first()
    val last = elk.sections.last()
    val source = nodes.getValue(edge.from).bounds
    val target = nodes.getValue(edge.to).bounds
    val start = ports?.source?.point ?: if (direction == LayoutDirection.VERTICAL) Point2D.Double(source.centerX, source.maxY)
        else Point2D.Double(source.maxX, source.centerY)
    val end = ports?.target?.point ?: if (direction == LayoutDirection.VERTICAL) Point2D.Double(target.centerX, target.minY)
        else Point2D.Double(target.minX, target.centerY)
    val exit = Point2D.Double(first.startX, first.startY)
    val entry = Point2D.Double(last.endX, last.endY)
    val points = buildList {
        add(start)
        if (direction == LayoutDirection.VERTICAL) add(Point2D.Double(start.x, exit.y))
        else add(Point2D.Double(exit.x, start.y))
        elk.sections.forEach { section ->
            add(Point2D.Double(section.startX, section.startY))
            section.bendPoints.forEach { add(Point2D.Double(it.x, it.y)) }
            add(Point2D.Double(section.endX, section.endY))
        }
        if (direction == LayoutDirection.VERTICAL) {
            add(Point2D.Double(entry.x, end.y - 18))
            add(Point2D.Double(end.x, end.y - 18))
        } else {
            add(Point2D.Double(end.x - 18, entry.y))
            add(Point2D.Double(end.x - 18, end.y))
        }
        add(end)
    }
    return corridor.copy(path = roundedEdgePath(points), arrowX = end.x, arrowY = end.y,
        arrowDx = if (direction == LayoutDirection.HORIZONTAL) 1.0 else 0.0,
        arrowDy = if (direction == LayoutDirection.VERTICAL) 1.0 else 0.0)
}

private fun Rectangle2D.Double.translated(x: Double, y: Double): Rectangle2D.Double =
    Rectangle2D.Double(this.x + x, this.y + y, width, height)

private fun PlacedEdge.translated(x: Double, y: Double): PlacedEdge = copy(
    path = java.awt.geom.Path2D.Double(AffineTransform.getTranslateInstance(x, y).createTransformedShape(path)),
    arrowX = arrowX + x, arrowY = arrowY + y, labelX = labelX + x, labelY = labelY + y,
    labelBounds = labelBounds?.translated(x, y),
)
