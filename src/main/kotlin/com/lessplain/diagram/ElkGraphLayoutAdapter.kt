package com.lessplain.diagram

import com.lessplain.model.GraphEdge
import com.lessplain.model.UnderstandingGraph
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.eclipse.elk.alg.layered.options.LayeredOptions
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

internal enum class LayoutDirection { VERTICAL, HORIZONTAL }

/** 所有 ELK 对象仅存在于此适配器，其余插件代码使用 AWT 几何。 */
internal object ElkGraphLayoutAdapter {
    fun layout(graph: UnderstandingGraph, direction: LayoutDirection): UnderstandingGraphLayout =
        layout(DiagramLayoutInput.prepare(graph, direction))

    fun layout(input: DiagramLayoutInput, separateLanes: Boolean = false): UnderstandingGraphLayout {
        val graph = input.graph
        val direction = input.direction
        val mainline = mainlineEdgeIds(graph)
        if (graph.nodes.isNotEmpty() && graph.edges.isEmpty() && graph.nodes.all { it.kind == "question" }) {
            return questionGrid(input)
        }
        val root = ElkGraphUtil.createGraph().apply {
            configureUnderstandingRouting(direction)
            setProperty(CoreOptions.SPACING_NODE_NODE, 52.0)
            setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS,
                if (direction == LayoutDirection.VERTICAL) 78.0 else if (separateLanes) DIAGRAM_LANE_GAP * 8 else 64.0)
            setProperty(CoreOptions.SPACING_COMPONENT_COMPONENT, 64.0)
        }
        val elkNodes = graph.nodes.associate { node ->
            node.id to ElkGraphUtil.createNode(root).apply {
                identifier = node.id
                val (width, height) = input.nodeSizes.getValue(node.id)
                setDimensions(width, height)
                setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
            }
        }
        val elkEdges = graph.edges.mapNotNull { edge ->
            val source = elkNodes[edge.from] ?: return@mapNotNull null
            val target = elkNodes[edge.to] ?: return@mapNotNull null
            edge to connect(edge, source, target, direction, input.edgeLabelSizes[edge.id],
                edge.id in mainline)
        }
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
        val placedNodes = graph.nodes.associateTo(linkedMapOf()) { node ->
            val elk = elkNodes.getValue(node.id)
            node.id to PlacedNode(node, Rectangle2D.Double(elk.x, elk.y, elk.width, elk.height), input.nodeFont)
        }
        val placedEdges = repairEdgeRoutes(placedNodes, elkEdges.map { (edge, elk) -> placedEdge(edge, elk) }, mainline,
            laneGap = if (separateLanes) DIAGRAM_LANE_GAP else 8.0, minimumSharedRun = if (separateLanes) 0.01 else 12.0)
        return UnderstandingGraphLayout(placedNodes, placedEdges, graphBounds(placedNodes.values, placedEdges))
    }

    internal fun connect(edge: GraphEdge, source: ElkNode, target: ElkNode,
        direction: LayoutDirection, labelSize: Pair<Double, Double>?,
        preferStraight: Boolean = false, sourceOrder: Int? = null, targetOrder: Int? = null): ElkEdge {
        val sourcePort = ElkGraphUtil.createPort(source).apply {
            identifier = "${edge.id}:out"
            setDimensions(0.0, 0.0)
            setProperty(CoreOptions.PORT_SIDE,
                if (direction == LayoutDirection.VERTICAL) PortSide.SOUTH else PortSide.EAST)
            sourceOrder?.let { setProperty(CoreOptions.PORT_INDEX, it) }
        }
        val targetPort = ElkGraphUtil.createPort(target).apply {
            identifier = "${edge.id}:in"
            setDimensions(0.0, 0.0)
            setProperty(CoreOptions.PORT_SIDE,
                if (direction == LayoutDirection.VERTICAL) PortSide.NORTH else PortSide.WEST)
            targetOrder?.let { setProperty(CoreOptions.PORT_INDEX, it) }
        }
        return ElkGraphUtil.createSimpleEdge(sourcePort, targetPort).apply {
            identifier = edge.id
            if (preferStraight) setProperty(LayeredOptions.PRIORITY_STRAIGHTNESS, 10)
            edge.condition?.takeIf(String::isNotBlank)?.let { condition ->
                ElkGraphUtil.createLabel(condition.take(32), this).apply {
                    val (width, height) = requireNotNull(labelSize)
                    setDimensions(width, height)
                }
            }
        }
    }

    internal fun placedEdge(edge: GraphEdge, elk: ElkEdge,
        offsetX: Double = 0.0, offsetY: Double = 0.0): PlacedEdge {
        val sections = elk.sections
        require(sections.isNotEmpty()) { "ELK 未输出边 ${edge.id} 的路径" }
        val path = Path2D.Double()
        sections.forEach { section ->
            path.append(roundedEdgePath(buildList {
                add(Point2D.Double(section.startX, section.startY))
                section.bendPoints.forEach { add(Point2D.Double(it.x, it.y)) }
                add(Point2D.Double(section.endX, section.endY))
            }), false)
        }
        val last = sections.last()
        val previous = last.bendPoints.lastOrNull()?.let { Point2D.Double(it.x, it.y) }
            ?: Point2D.Double(last.startX, last.startY)
        val label = elk.labels.firstOrNull()
        val labelX = label?.let { it.x + it.width / 2 } ?: path.bounds2D.centerX
        val labelY = label?.let { it.y + it.height / 2 } ?: path.bounds2D.centerY
        val transformed = Path2D.Double(AffineTransform.getTranslateInstance(offsetX, offsetY)
            .createTransformedShape(path))
        return PlacedEdge(edge, transformed, last.endX + offsetX, last.endY + offsetY,
            last.endX - previous.x, last.endY - previous.y, labelX + offsetX, labelY + offsetY,
            label?.let { Rectangle2D.Double(it.x + offsetX, it.y + offsetY, it.width, it.height) })
    }

    private fun questionGrid(input: DiagramLayoutInput): UnderstandingGraphLayout {
        val nodes = input.graph.nodes.mapIndexed { index, node ->
            val (width, height) = input.nodeSizes.getValue(node.id)
            node.id to PlacedNode(node, Rectangle2D.Double((index % 2) * 370.0, (index / 2) * 130.0,
                width, height), input.nodeFont)
        }.toMap(LinkedHashMap())
        return UnderstandingGraphLayout(nodes, emptyList(), graphBounds(nodes.values, emptyList()))
    }
}

internal fun graphBounds(nodes: Collection<PlacedNode>, edges: List<PlacedEdge>): Rectangle2D.Double {
    val bounds = Rectangle2D.Double()
    nodes.firstOrNull()?.bounds?.let(bounds::setRect)
    nodes.drop(1).forEach { bounds.add(it.bounds) }
    edges.forEach { edge ->
        bounds.add(edge.paintBounds)
    }
    if (nodes.isEmpty()) bounds.setRect(0.0, 0.0, 220.0, 72.0)
    return Rectangle2D.Double(bounds.x - 95, bounds.y - 95, bounds.width + 190, bounds.height + 190)
}
