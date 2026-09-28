package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.AffineTransform
import kotlin.math.max

internal data class PlacedNode(val node: GraphNode, val bounds: Rectangle2D.Double)

internal data class PlacedEdge(
    val edge: GraphEdge,
    val path: Path2D.Double,
    val arrowX: Double,
    val arrowY: Double,
    val arrowDx: Double,
    val arrowDy: Double,
    val labelX: Double,
    val labelY: Double,
)

internal data class UnderstandingGraphLayout(
    val nodes: Map<String, PlacedNode>,
    val edges: List<PlacedEdge>,
    val bounds: Rectangle2D.Double,
    val clusters: List<PlacedCluster> = emptyList(),
)

internal data class DiagramCluster(val id: String, val title: String, val nodeIds: Set<String>)
internal data class PlacedCluster(
    val id: String,
    val title: String,
    val bounds: Rectangle2D.Double,
    val exampleBounds: Rectangle2D.Double? = null,
    val nodeIds: Set<String> = emptySet(),
)

internal fun examplePositionKey(clusterId: String): String = "@example-panel:$clusterId"

/** Lay out each answer inside one shared, vertically navigable canvas. */
internal fun layoutUnderstandingClusters(
    graph: UnderstandingGraph,
    clusters: List<DiagramCluster>,
    examplePanels: List<DiagramExamplePanel> = emptyList(),
): UnderstandingGraphLayout {
    val nodes = linkedMapOf<String, PlacedNode>()
    val edges = mutableListOf<PlacedEdge>()
    val placedClusters = mutableListOf<PlacedCluster>()
    var top = 0.0
    clusters.forEach { cluster ->
        val ids = cluster.nodeIds
        val part = layoutUnderstandingGraph(UnderstandingGraph(
            graph.nodes.filter { it.id in ids },
            graph.edges.filter { it.from in ids && it.to in ids },
        ))
        val hasExample = examplePanels.any { it.clusterId == cluster.id }
        val graphWidth = max(560.0, part.bounds.width + 60.0)
        val clusterWidth = graphWidth + if (hasExample) DiagramExamplePainter.PANEL_WIDTH + 48.0 else 0.0
        val clusterHeight = max(part.bounds.height + 90.0, if (hasExample) DiagramExamplePainter.PANEL_HEIGHT + 90.0 else 0.0)
        val dx = -part.bounds.centerX - if (hasExample) (DiagramExamplePainter.PANEL_WIDTH + 48.0) / 2 else 0.0
        val dy = top + 55.0 - part.bounds.y
        part.nodes.forEach { (id, placed) ->
            val b = placed.bounds
            nodes[id] = placed.copy(bounds = Rectangle2D.Double(b.x + dx, b.y + dy, b.width, b.height))
        }
        val transform = AffineTransform.getTranslateInstance(dx, dy)
        part.edges.forEach { edge ->
            edges += edge.copy(
                path = Path2D.Double(transform.createTransformedShape(edge.path)),
                arrowX = edge.arrowX + dx,
                arrowY = edge.arrowY + dy,
                labelX = edge.labelX + dx,
                labelY = edge.labelY + dy,
            )
        }
        val exampleBounds = if (hasExample) Rectangle2D.Double(
            clusterWidth / 2 - DiagramExamplePainter.PANEL_WIDTH - 24.0,
            top + (clusterHeight - DiagramExamplePainter.PANEL_HEIGHT) / 2,
            DiagramExamplePainter.PANEL_WIDTH.toDouble(), DiagramExamplePainter.PANEL_HEIGHT.toDouble(),
        ) else null
        placedClusters += PlacedCluster(cluster.id, cluster.title,
            Rectangle2D.Double(-clusterWidth / 2, top, clusterWidth, clusterHeight), exampleBounds, ids)
        top += clusterHeight + 48.0
    }
    val width = placedClusters.maxOfOrNull { it.bounds.width } ?: 560.0
    return UnderstandingGraphLayout(nodes, edges,
        Rectangle2D.Double(-width / 2 - 40, -40.0, width + 80, top + 32.0), placedClusters)
}

/** Place control flow in reading order; cycles and disconnected nodes get stable fallback layers. */
internal fun layoutUnderstandingGraph(graph: UnderstandingGraph): UnderstandingGraphLayout {
    val questionIndex = graph.nodes.isNotEmpty() && graph.nodes.all { it.kind == "question" }
    val nodeWidth = if (questionIndex) 330.0 else NODE_WIDTH
    val nodeHeight = if (questionIndex) 96.0 else NODE_HEIGHT
    val columnGap = if (questionIndex) 390.0 else COLUMN_GAP
    val rowGap = if (questionIndex) 145.0 else ROW_GAP
    val byId = graph.nodes.associateBy(GraphNode::id)
    val layoutEdges = graph.edges.filter { it.from in byId && it.to in byId && it.from != it.to }
    val outgoing = layoutEdges.groupBy(GraphEdge::from)
    val incoming = layoutEdges.groupBy(GraphEdge::to)
    val indegree = graph.nodes.associate { it.id to 0 }.toMutableMap()
    layoutEdges.forEach { indegree[it.to] = indegree.getValue(it.to) + 1 }
    val layers = graph.nodes.associate { it.id to 0 }.toMutableMap()
    val ready = ArrayDeque(graph.nodes.filter { indegree.getValue(it.id) == 0 }.map(GraphNode::id))
    val ordered = mutableSetOf<String>()
    while (ready.isNotEmpty()) {
        val id = ready.removeFirst()
        if (!ordered.add(id)) continue
        outgoing[id].orEmpty().forEach { edge ->
            layers[edge.to] = max(layers.getValue(edge.to), layers.getValue(id) + 1)
            indegree[edge.to] = indegree.getValue(edge.to) - 1
            if (indegree.getValue(edge.to) == 0) ready.add(edge.to)
        }
    }
    var fallbackLayer = (ordered.maxOfOrNull { layers.getValue(it) } ?: -1) + 1
    graph.nodes.filter { it.id !in ordered }.forEach { node -> layers[node.id] = fallbackLayer++ }

    val placed = linkedMapOf<String, PlacedNode>()
    if (layoutEdges.isEmpty()) {
        graph.nodes.forEachIndexed { index, node ->
            val x = (index % 2 - 0.5) * columnGap - nodeWidth / 2
            val y = (index / 2) * rowGap
            placed[node.id] = PlacedNode(node, Rectangle2D.Double(x, y, nodeWidth, nodeHeight))
        }
    } else {
        val originalOrder = graph.nodes.mapIndexed { index, node -> node.id to index }.toMap()
        val orderedPositions = mutableMapOf<String, Double>()
        graph.nodes.groupBy { layers.getValue(it.id) }.toSortedMap().forEach { (layer, items) ->
            val sorted = items.sortedWith(compareBy<GraphNode> { node ->
                incoming[node.id].orEmpty().mapNotNull { orderedPositions[it.from] }.average()
                    .takeUnless(Double::isNaN) ?: originalOrder.getValue(node.id).toDouble()
            }.thenBy { originalOrder.getValue(it.id) })
            sorted.forEachIndexed { index, node ->
                val x = (index - (sorted.size - 1) / 2.0) * columnGap - nodeWidth / 2
                val y = layer * rowGap
                placed[node.id] = PlacedNode(node, Rectangle2D.Double(x, y, nodeWidth, nodeHeight))
                orderedPositions[node.id] = index.toDouble()
            }
        }
    }
    val edges = routeUnderstandingEdges(graph, placed)
    val allBounds = Rectangle2D.Double()
    placed.values.firstOrNull()?.bounds?.let { allBounds.setRect(it) }
    placed.values.drop(1).forEach { allBounds.add(it.bounds) }
    edges.forEach { allBounds.add(it.path.bounds2D) }
    if (placed.isEmpty()) allBounds.setRect(-nodeWidth / 2, 0.0, nodeWidth, nodeHeight)
    allBounds.setRect(allBounds.x - MARGIN, allBounds.y - MARGIN, allBounds.width + MARGIN * 2, allBounds.height + MARGIN * 2)
    return UnderstandingGraphLayout(placed, edges, allBounds)
}

/** Re-route connectors after a node is dragged, while retaining the automatic layout as the reset point. */
internal fun positionUnderstandingNodes(
    graph: UnderstandingGraph,
    base: UnderstandingGraphLayout,
    positions: Map<String, Point2D.Double>,
): UnderstandingGraphLayout {
    if (positions.isEmpty()) return base
    val nodes = base.nodes.mapValues { (id, placed) ->
        val point = positions[id]?.takeIf { it.x.isFinite() && it.y.isFinite() }
        if (point == null) placed else placed.copy(bounds = Rectangle2D.Double(
            point.x, point.y, placed.bounds.width, placed.bounds.height,
        ))
    }
    val edges = routeUnderstandingEdges(graph, nodes)
    val clusters = base.clusters.map { cluster ->
        val bounds = Rectangle2D.Double().apply { setRect(cluster.bounds) }
        val exampleBounds = cluster.exampleBounds?.let { original ->
            val point = positions[examplePositionKey(cluster.id)]?.takeIf { it.x.isFinite() && it.y.isFinite() }
            if (point == null) original else Rectangle2D.Double(point.x, point.y, original.width, original.height)
        }
        exampleBounds?.let { panel ->
            bounds.add(Rectangle2D.Double(panel.x - 24, panel.y - 42,
                panel.width + 48, panel.height + 94))
        }
        cluster.nodeIds.mapNotNull(nodes::get).forEach { placed ->
            val b = placed.bounds
            bounds.add(Rectangle2D.Double(b.x - 24, b.y - 42, b.width + 48, b.height + 94))
        }
        edges.filter { it.edge.from in cluster.nodeIds && it.edge.to in cluster.nodeIds }
            .forEach { edge ->
                val b = edge.path.bounds2D
                bounds.add(Rectangle2D.Double(b.x - 16, b.y - 16, b.width + 32, b.height + 32))
            }
        cluster.copy(bounds = bounds, exampleBounds = exampleBounds)
    }
    val bounds = Rectangle2D.Double().apply { setRect(base.bounds) }
    nodes.values.forEach { bounds.add(it.bounds) }
    edges.forEach { bounds.add(it.path.bounds2D) }
    clusters.forEach { bounds.add(it.bounds) }
    bounds.setRect(bounds.x - MARGIN, bounds.y - MARGIN,
        bounds.width + MARGIN * 2, bounds.height + MARGIN * 2)
    return UnderstandingGraphLayout(nodes, edges, bounds, clusters)
}

private fun routeUnderstandingEdges(
    graph: UnderstandingGraph,
    placed: Map<String, PlacedNode>,
): List<PlacedEdge> {
    val parallel = mutableMapOf<Pair<String, String>, Int>()
    val outgoingPorts = graph.edges.groupBy(GraphEdge::from).mapValues { (_, edges) ->
        edges.sortedWith(compareBy<GraphEdge> { placed[it.to]?.bounds?.centerX ?: 0.0 }.thenBy { it.id })
            .mapIndexed { index, edge -> edge.id to (index - (edges.size - 1) / 2.0) * 18.0 }.toMap()
    }
    val incomingPorts = graph.edges.groupBy(GraphEdge::to).mapValues { (_, edges) ->
        edges.sortedWith(compareBy<GraphEdge> { placed[it.from]?.bounds?.centerX ?: 0.0 }.thenBy { it.id })
            .mapIndexed { index, edge -> edge.id to (index - (edges.size - 1) / 2.0) * 18.0 }.toMap()
    }
    return graph.edges.mapNotNull { edge ->
        val source = placed[edge.from]?.bounds ?: return@mapNotNull null
        val target = placed[edge.to]?.bounds ?: return@mapNotNull null
        val key = edge.from to edge.to
        val offset = parallel.getOrDefault(key, 0).also { parallel[key] = it + 1 } * 22.0
        placeEdge(edge, source, target, offset,
            outgoingPorts[edge.from]?.get(edge.id) ?: 0.0,
            incomingPorts[edge.to]?.get(edge.id) ?: 0.0)
    }
}

private fun placeEdge(
    edge: GraphEdge,
    from: Rectangle2D.Double,
    to: Rectangle2D.Double,
    offset: Double,
    sourcePort: Double,
    targetPort: Double,
): PlacedEdge {
    val path = Path2D.Double()
    val sx: Double
    val sy: Double
    val tx: Double
    val ty: Double
    val control1X: Double
    val control1Y: Double
    val control2X: Double
    val control2Y: Double
    when {
        from === to -> {
            sx = from.maxX - 20
            sy = from.y
            tx = from.maxX
            ty = from.centerY
            control1X = from.maxX + 90 + offset
            control1Y = from.y - 85 - offset
            control2X = from.maxX + 110 + offset
            control2Y = from.centerY
        }
        to.y >= from.maxY + 12 -> {
            sx = from.centerX + sourcePort + offset
            sy = from.maxY
            tx = to.centerX + targetPort + offset
            ty = to.y
            val middle = (sy + ty) / 2
            control1X = sx
            control1Y = middle
            control2X = tx
            control2Y = middle
        }
        to.centerY <= from.centerY -> {
            sx = from.maxX
            sy = from.centerY
            tx = to.maxX
            ty = to.centerY
            val outer = max(from.maxX, to.maxX) + 85 + offset
            control1X = outer
            control1Y = sy
            control2X = outer
            control2Y = ty
        }
        else -> {
            val forward = to.centerX >= from.centerX
            sx = if (forward) from.maxX else from.x
            sy = from.centerY
            tx = if (forward) to.x else to.maxX
            ty = to.centerY
            control1X = (sx + tx) / 2
            control1Y = sy
            control2X = control1X
            control2Y = ty
        }
    }
    path.moveTo(sx, sy)
    path.curveTo(control1X, control1Y, control2X, control2Y, tx, ty)
    val labelX = (sx + 3 * control1X + 3 * control2X + tx) / 8
    val labelY = (sy + 3 * control1Y + 3 * control2Y + ty) / 8
    return PlacedEdge(edge, path, tx, ty, tx - control2X, ty - control2Y, labelX, labelY)
}

private const val NODE_WIDTH = 220.0
private const val NODE_HEIGHT = 72.0
private const val COLUMN_GAP = 300.0
private const val ROW_GAP = 170.0
private const val MARGIN = 95.0
