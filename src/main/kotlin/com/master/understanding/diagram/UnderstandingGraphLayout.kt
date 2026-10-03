package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D

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
    val labelBounds: Rectangle2D.Double? = null,
) {
    val arrowPath: Path2D.Double by lazy { edgeArrowPath(this) }
    val resolvedLabelBounds: Rectangle2D.Double? by lazy { edgeLabelBounds(this) }
    val paintBounds: Rectangle2D.Double by lazy { edgePaintBounds(this) }
    val segments: List<RoutingSegment> by lazy { routingSegments(this) }
}

internal data class UnderstandingGraphLayout(
    val nodes: Map<String, PlacedNode>,
    val edges: List<PlacedEdge>,
    val bounds: Rectangle2D.Double,
    val clusters: List<PlacedCluster> = emptyList(),
) {
    val crossings: List<DiagramCrossing> by lazy { diagramCrossings(edges) }
}

internal data class DiagramCluster(
    val id: String,
    val title: String,
    val nodeIds: Set<String>,
)
internal data class PlacedCluster(
    val id: String,
    val title: String,
    val bounds: Rectangle2D.Double,
    val exampleBounds: Rectangle2D.Double? = null,
    val nodeIds: Set<String> = emptySet(),
    val textLayout: ClusterTextLayout = DiagramClusterText.measure(title, bounds.width),
)

internal fun examplePositionKey(clusterId: String): String = "@example-panel:$clusterId"

/** Preserve follow-up groups while ELK routes all relations, including cross-group edges. */
internal fun layoutUnderstandingClusters(
    graph: UnderstandingGraph,
    clusters: List<DiagramCluster>,
    examplePanels: List<DiagramExamplePanel> = emptyList(),
    direction: LayoutDirection = LayoutDirection.VERTICAL,
    routingVersion: ClusterRoutingVersion = ClusterRoutingVersion.SEPARATE_LANES,
): UnderstandingGraphLayout = ElkClusterLayoutAdapter.layout(graph, clusters, examplePanels, direction, routingVersion)

/** Layout entry point for a standalone graph. */
internal fun layoutUnderstandingGraph(graph: UnderstandingGraph): UnderstandingGraphLayout =
    ElkGraphLayoutAdapter.layout(graph, LayoutDirection.VERTICAL)

internal fun layoutUnderstandingGraph(graph: UnderstandingGraph, direction: LayoutDirection): UnderstandingGraphLayout =
    ElkGraphLayoutAdapter.layout(graph, direction)

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
    val edges = routeMovedEdges(base, nodes, mainlineEdgeIds(graph))
    val clusters = base.clusters.map { cluster ->
        if (positions.keys.none { it in cluster.nodeIds || it == examplePositionKey(cluster.id) })
            return@map cluster
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
    edges.forEach { bounds.add(it.paintBounds) }
    clusters.forEach { bounds.add(it.bounds) }
    bounds.setRect(bounds.x - MARGIN, bounds.y - MARGIN,
        bounds.width + MARGIN * 2, bounds.height + MARGIN * 2)
    return UnderstandingGraphLayout(nodes, edges, bounds, clusters)
}

private const val MARGIN = 95.0
