package com.master.understanding.diagram

import com.master.understanding.model.UnderstandingGraph
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.util.ElkGraphUtil

/** Layout each group's content first, then arrange fixed-size groups by their actual relations. */
internal object ElkClusterLayoutAdapter {
    fun layout(graph: UnderstandingGraph, clusters: List<DiagramCluster>,
        examplePanels: List<DiagramExamplePanel>, direction: LayoutDirection): UnderstandingGraphLayout {
        val covered = clusters.flatMapTo(hashSetOf()) { it.nodeIds }
        val ungrouped = graph.nodes.mapTo(linkedSetOf()) { it.id }.apply { removeAll(covered) }
        val groups = if (ungrouped.isEmpty()) clusters else clusters +
            DiagramCluster("@ungrouped", "其他步骤", ungrouped)
        val contents = groups.associate { group ->
            group.id to layoutClusterContent(graph, group, examplePanels.firstOrNull { it.clusterId == group.id }, direction)
        }
        val owner = groups.flatMap { group -> group.nodeIds.map { it to group.id } }.toMap()
        val mainline = mainlineEdgeIds(graph)
        val root = ElkGraphUtil.createGraph().apply {
            configureUnderstandingRouting(direction)
            setProperty(CoreOptions.SPACING_NODE_NODE, 72.0)
            setProperty(CoreOptions.SPACING_COMPONENT_COMPONENT, 72.0)
            setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 88.0)
        }
        val elkGroups = groups.associate { group ->
            val content = contents.getValue(group.id)
            group.id to ElkGraphUtil.createNode(root).apply {
                identifier = group.id
                setDimensions(content.cluster.bounds.width, content.cluster.bounds.height)
                setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
            }
        }
        val input = DiagramLayoutInput.prepare(graph, direction)
        val crossEdges = graph.edges.filter { owner[it.from] != owner[it.to] }.map { edge ->
            edge to ElkGraphLayoutAdapter.connect(edge, elkGroups.getValue(owner.getValue(edge.from)),
                elkGroups.getValue(owner.getValue(edge.to)), direction, input.edgeLabelSizes[edge.id], edge.id in mainline)
        }
        // Shared question canvases have independent records, whose reading order must remain stable.
        // A graph with real cross-group relations must never acquire artificial serial dependencies.
        if (crossEdges.isEmpty()) groups.zipWithNext().forEachIndexed { index, (first, second) ->
            ElkGraphUtil.createSimpleEdge(elkGroups.getValue(first.id), elkGroups.getValue(second.id))
                .identifier = "@cluster-order:$index"
        }
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
        val placedContents = groups.map { group ->
            val elk = elkGroups.getValue(group.id)
            contents.getValue(group.id).translated(elk.x, elk.y)
        }
        val nodesById = placedContents.flatMap { it.nodes.entries }.associate { it.key to it.value }
        val nodes = graph.nodes.associateTo(linkedMapOf()) { it.id to nodesById.getValue(it.id) }
        val insideEdges = placedContents.flatMap { it.edges }.associateBy { it.edge.id }
        val outsideEdges = crossEdges.associate { (edge, elk) ->
            edge.id to connectClusterContent(edge, elk, nodes, direction)
        }
        val clustersPlaced = placedContents.map { it.cluster }
        val edges = graph.edges.map { insideEdges[it.id] ?: outsideEdges.getValue(it.id) }
        val repaired = repairEdgeRoutes(nodes, edges, mainline, obstacles = clusterRoutingObstacles(clustersPlaced),
            edgeObstacles = unrelatedClusterObstacles(clustersPlaced, edges), stableEdgeIds = insideEdges.keys)
        val bounds = graphBounds(nodes.values, repaired)
        clustersPlaced.forEach { bounds.add(it.bounds) }
        return UnderstandingGraphLayout(nodes, repaired, bounds, clustersPlaced)
    }
}
