package com.master.understanding.diagram

import com.master.understanding.model.UnderstandingGraph
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.math.KVector
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.SizeConstraint
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.eclipse.elk.alg.layered.options.LayeredOptions
import java.awt.geom.Rectangle2D
import kotlin.math.max

/** Compound ELK layout retains cluster order and routes cross-cluster edges. */
internal object ElkClusterLayoutAdapter {
    fun layout(graph: UnderstandingGraph, clusters: List<DiagramCluster>,
        examplePanels: List<DiagramExamplePanel>, direction: LayoutDirection): UnderstandingGraphLayout =
        layout(DiagramLayoutInput.prepare(graph, direction, clusters, examplePanels))

    private fun layout(input: DiagramLayoutInput): UnderstandingGraphLayout {
        val graph = input.graph
        val clusters = input.clusters
        val examplePanels = input.examplePanels
        val direction = input.direction
        val mainline = mainlineEdgeIds(graph)
        val covered = clusters.flatMapTo(hashSetOf()) { it.nodeIds }
        val ungrouped = graph.nodes.mapTo(linkedSetOf()) { it.id }.apply { removeAll(covered) }
        val groups = if (ungrouped.isEmpty()) clusters else clusters +
            DiagramCluster("@ungrouped", "其他步骤", ungrouped)
        val root = ElkGraphUtil.createGraph().apply {
            configureUnderstandingRouting(direction)
            setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
            setProperty(CoreOptions.SPACING_NODE_NODE, 72.0)
            setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS,
                if (direction == LayoutDirection.VERTICAL) 88.0 else 72.0)
        }
        val elkGroups = groups.associate { group ->
            group.id to ElkGraphUtil.createNode(root).apply {
                identifier = group.id
                val minimumWidth = 360.0
                val titleHeight = DiagramClusterText.measure(group.title, minimumWidth).height
                val topPadding = max(84.0, titleHeight + 12.0)
                configureUnderstandingRouting(direction)
                setProperty(CoreOptions.PADDING, ElkPadding(topPadding, 28.0, 32.0, 28.0))
                setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, SizeConstraint.minimumSize())
                setProperty(CoreOptions.NODE_SIZE_MINIMUM, KVector(minimumWidth, topPadding + 32.0))
                setProperty(CoreOptions.SPACING_NODE_NODE, 46.0)
                setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS,
                    if (direction == LayoutDirection.VERTICAL) 78.0 else 64.0)
            }
        }
        val owner = graph.nodes.associate { node ->
            node.id to groups.firstOrNull { node.id in it.nodeIds }?.id
        }
        val elkNodes = graph.nodes.associate { node ->
            val parent = elkGroups[owner[node.id]] ?: root
            node.id to ElkGraphUtil.createNode(parent).apply {
                identifier = node.id
                val (width, height) = input.nodeSizes.getValue(node.id)
                setDimensions(width, height)
                setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
            }
        }
        val realEdges = graph.edges.mapNotNull { edge ->
            val from = elkNodes[edge.from] ?: return@mapNotNull null
            val to = elkNodes[edge.to] ?: return@mapNotNull null
            edge to ElkGraphLayoutAdapter.connect(edge, from, to, direction,
                input.edgeLabelSizes[edge.id], edge.id in mainline)
        }
        groups.zipWithNext().forEachIndexed { index, (first, second) ->
            ElkGraphUtil.createSimpleEdge(elkGroups.getValue(first.id), elkGroups.getValue(second.id))
                .identifier = "@cluster-order:$index"
        }
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
        val placedNodes = graph.nodes.associateTo(linkedMapOf()) { node ->
            val elk = elkNodes.getValue(node.id)
            val (offsetX, offsetY) = absoluteOrigin(elk.parent, root)
            node.id to PlacedNode(node, Rectangle2D.Double(elk.x + offsetX, elk.y + offsetY,
                elk.width, elk.height))
        }
        val placedEdges = realEdges.map { (edge, elk) ->
            val (offsetX, offsetY) = absoluteOrigin(elk.containingNode, root)
            ElkGraphLayoutAdapter.placedEdge(edge, elk, offsetX, offsetY)
        }
        val placedClusters = groups.map { group ->
            val elk = elkGroups.getValue(group.id)
            val box = Rectangle2D.Double(elk.x, elk.y, max(elk.width, 240.0), max(elk.height, 96.0))
            val panel = examplePanels.firstOrNull { it.clusterId == group.id }?.let {
                Rectangle2D.Double(box.maxX + 20, box.y + 84,
                    DiagramExamplePainter.PANEL_WIDTH.toDouble(), DiagramExamplePainter.PANEL_HEIGHT.toDouble())
            }
            if (panel != null) box.add(Rectangle2D.Double(panel.x - 12, panel.y - 12,
                panel.width + 24, panel.height + 24))
            PlacedCluster(group.id, group.title, box, panel, group.nodeIds,
                DiagramClusterText.measure(group.title, box.width))
        }
        val repairedEdges = repairEdgeRoutes(placedNodes, placedEdges, mainline, obstacles = clusterRoutingObstacles(placedClusters))
        val bounds = graphBounds(placedNodes.values, repairedEdges)
        placedClusters.forEach { bounds.add(it.bounds) }
        return UnderstandingGraphLayout(placedNodes, repairedEdges, bounds, placedClusters)
    }

    private fun absoluteOrigin(node: ElkNode?, root: ElkNode): Pair<Double, Double> {
        var current = node
        var x = 0.0
        var y = 0.0
        while (current != null && current !== root) {
            x += current.x
            y += current.y
            current = current.parent
        }
        return x to y
    }
}
