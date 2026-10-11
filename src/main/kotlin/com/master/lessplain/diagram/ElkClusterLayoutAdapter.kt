package com.master.lessplain.diagram

import com.master.lessplain.model.UnderstandingGraph
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.util.ElkGraphUtil

/** 先布局各组内容，再按实际关系排列尺寸固定的分组。 */
internal object ElkClusterLayoutAdapter {
    fun layout(graph: UnderstandingGraph, clusters: List<DiagramCluster>,
        examplePanels: List<DiagramExamplePanel>, direction: LayoutDirection,
        routingVersion: ClusterRoutingVersion = ClusterRoutingVersion.SEPARATE_LANES): UnderstandingGraphLayout {
        if (routingVersion == ClusterRoutingVersion.SEPARATE_LANES) {
            return ReadingClusterLayout.layout(graph, clusters, examplePanels, direction)
        }
        var best: UnderstandingGraphLayout? = null
        var bestDefects = Int.MAX_VALUE
        for (attempt in 0..3) {
            val result = layoutOnce(graph, clusters, examplePanels, direction, routingVersion, 88.0 + attempt * 96)
            if (routingVersion == ClusterRoutingVersion.LEGACY) return result
            val defects = clusterRouteDefects(result)
            if (defects < bestDefects) { best = result; bestDefects = defects }
            if (defects == 0) return result
        }
        return requireNotNull(best)
    }

    private fun layoutOnce(graph: UnderstandingGraph, clusters: List<DiagramCluster>,
        examplePanels: List<DiagramExamplePanel>, direction: LayoutDirection,
        routingVersion: ClusterRoutingVersion, spacing: Double): UnderstandingGraphLayout {
        val separateLanes = routingVersion == ClusterRoutingVersion.SEPARATE_LANES
        val covered = clusters.flatMapTo(hashSetOf()) { it.nodeIds }
        val ungrouped = graph.nodes.mapTo(linkedSetOf()) { it.id }.apply { removeAll(covered) }
        val groups = if (ungrouped.isEmpty()) clusters else clusters +
            DiagramCluster("@ungrouped", "其他步骤", ungrouped)
        val contents = groups.associate { group ->
            group.id to layoutClusterContent(graph, group, examplePanels.firstOrNull { it.clusterId == group.id }, direction, separateLanes)
        }
        val owner = groups.flatMap { group -> group.nodeIds.map { it to group.id } }.toMap()
        val mainline = mainlineEdgeIds(graph)
        val root = ElkGraphUtil.createGraph().apply {
            configureUnderstandingRouting(direction)
            setProperty(CoreOptions.SPACING_NODE_NODE, spacing - 16)
            setProperty(CoreOptions.SPACING_COMPONENT_COMPONENT, 72.0)
            setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, spacing)
        }
        val elkGroups = groups.associate { group ->
            val content = contents.getValue(group.id)
            group.id to ElkGraphUtil.createNode(root).apply {
                identifier = group.id
                setDimensions(content.cluster.bounds.width, content.cluster.bounds.height)
                setProperty(CoreOptions.PORT_CONSTRAINTS, if (separateLanes) PortConstraints.FIXED_ORDER else PortConstraints.FIXED_SIDE)
            }
        }
        val input = DiagramLayoutInput.prepare(graph, direction)
        val external = graph.edges.filter { owner[it.from] != owner[it.to] }
        fun portOrders(source: Boolean): Map<String, Int> = external.groupBy { owner.getValue(if (source) it.from else it.to) }
            .values.flatMap { edges ->
                val sorted = edges.sortedWith(compareBy<com.master.lessplain.model.GraphEdge> {
                    val id = if (source) it.from else it.to
                    val box = contents.getValue(owner.getValue(id)).nodes.getValue(id).bounds
                    if (direction == LayoutDirection.VERTICAL) box.centerX else box.centerY
                }.thenBy { it.id })
                val reverse = source == (direction == LayoutDirection.VERTICAL)
                (if (reverse) sorted.reversed() else sorted).mapIndexed { index, edge -> edge.id to index }
            }.toMap()
        val sourceOrders = if (separateLanes) portOrders(true) else emptyMap()
        val targetOrders = if (separateLanes) portOrders(false) else emptyMap()
        val crossEdges = external.map { edge ->
            edge to ElkGraphLayoutAdapter.connect(edge, elkGroups.getValue(owner.getValue(edge.from)),
                elkGroups.getValue(owner.getValue(edge.to)), direction, input.edgeLabelSizes[edge.id], edge.id in mainline,
                sourceOrders[edge.id], targetOrders[edge.id])
        }
        // 共享追问画布包含独立记录，必须保留各记录的阅读顺序。
        // 有真实跨组关系的图不能额外引入人为的串行依赖。
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
        val ports = if (separateLanes) allocateClusterPorts(nodes, insideEdges.values, external, direction) else emptyMap()
        val outsideEdges = crossEdges.associate { (edge, elk) ->
            edge.id to connectClusterContent(edge, elk, nodes, direction, ports[edge.id])
        }
        val clustersPlaced = placedContents.map { it.cluster }
        val edges = graph.edges.map { insideEdges[it.id] ?: outsideEdges.getValue(it.id) }
        val initialRoutes = repairEdgeRoutes(nodes, edges, mainline, obstacles = clusterRoutingObstacles(clustersPlaced),
            edgeObstacles = unrelatedClusterObstacles(clustersPlaced, edges), stableEdgeIds = insideEdges.keys,
            laneGap = if (separateLanes) DIAGRAM_LANE_GAP else 8.0,
            minimumSharedRun = if (separateLanes) 0.01 else 12.0, fixedPorts = ports,
            clusterBorders = if (separateLanes) clusterBorderBounds(clustersPlaced, edges) else emptyMap())
        val repaired = if (separateLanes) refineClusterLanes(nodes, initialRoutes, clustersPlaced, ports) else initialRoutes
        val bounds = graphBounds(nodes.values, repaired)
        clustersPlaced.forEach { bounds.add(it.bounds) }
        return UnderstandingGraphLayout(nodes, repaired, bounds, clustersPlaced)
    }
}
