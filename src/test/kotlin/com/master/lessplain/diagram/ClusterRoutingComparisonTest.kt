package com.master.lessplain.diagram

import com.google.gson.GsonBuilder
import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.UnderstandingGraph
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.min
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fixed inputs produce comparable geometry and offscreen artifacts, without launching an IDE. */
class ClusterRoutingComparisonTest {
    @Test fun `compare ordinary legacy and separate lanes on identical graphs and groups`() {
        val output = Path.of("build/reports/cluster-routing")
        Files.createDirectories(output)
        val results = mutableListOf<RoutingComparison>()
        val failures = mutableListOf<String>()
        // Initialize ELK and font services outside every sample's timing.
        layoutUnderstandingGraph(UnderstandingGraph(listOf(GraphNode("warmup", "预热")), emptyList()))
        val samples = listOf(
            "article-publish" to groupedFlowFixture("article-publish-clusters"),
            "paid-article" to groupedFlowFixture("paid-article-clusters"),
            "dense-fork-join" to denseGraph(),
        )
        for ((name, sample) in samples) for (direction in LayoutDirection.entries) {
            val (graph, groups) = sample
            for (version in listOf("ordinary", "legacy", "separate-lanes", "regrouped")) {
                lateinit var layout: UnderstandingGraphLayout
                val timings = (0..2).map {
                    measureNanoTime { layout = when (version) {
                        "ordinary" -> layoutUnderstandingGraph(graph, direction)
                        "legacy" -> layoutUnderstandingClusters(graph, groups, direction = direction,
                            routingVersion = ClusterRoutingVersion.LEGACY)
                        "regrouped" -> layoutUnderstandingClusters(graph, regroup(name, groups), direction = direction)
                        else -> layoutUnderstandingClusters(graph, groups, direction = direction)
                    } } / 1_000_000.0
                }
                val elapsed = timings.sorted()[1]
                assertEquals(graph.nodes.map { it.id }, layout.nodes.keys.toList())
                assertEquals(graph.edges.map { it.id }, layout.edges.map { it.edge.id })
                val quality = quality(layout)
                results += RoutingComparison(name, direction.name, version, graph.nodes.size, graph.edges.size,
                    elapsed, layout.bounds.width, layout.bounds.height, quality)
                val key = "$name-${direction.name.lowercase()}-$version"
                render(layout, output.resolve("$key.png"))
                RoutingArtifacts.writeSvg(layout, output.resolve("$key.svg"))
                if (version == "separate-lanes") {
                    val repeated = layoutUnderstandingClusters(graph, groups, direction = direction)
                    assertEquals(layout.nodes.mapValues { it.value.bounds }, repeated.nodes.mapValues { it.value.bounds })
                    assertEquals(layout.edges.map { it.path.bounds2D }, repeated.edges.map { it.path.bounds2D })
                    if (quality.contentConflicts != 0 || quality.laneConflictPairs.isNotEmpty()) {
                        failures += "$key: $quality"
                    }
                }
            }
        }
        val json = GsonBuilder().setPrettyPrinting().create().toJson(results)
        Files.writeString(output.resolve("metrics.json"), json)
        RoutingArtifacts.writeGallery(json, output.resolve("index.html"))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun quality(layout: UnderstandingGraphLayout): RoutingQuality {
        val scene = EdgeRoutingObstacles(layout.nodes.values, clusterRoutingObstacles(layout.clusters),
            unrelatedClusterObstacles(layout.clusters, layout.edges), DIAGRAM_LANE_GAP, 0.01)
        layout.edges.forEach(scene::add)
        val content = layout.edges.count { scene.crossesNode(it) || scene.labelCrossesNode(it) ||
            it.resolvedLabelBounds?.intersects(it.arrowPath.bounds2D) == true }
        val pairs = layout.edges.flatMap { edge -> scene.conflicts(edge).map {
            listOf(edge.edge.id, it).sorted().joinToString(" / ")
        } }.distinct().sorted()
        return RoutingQuality(content, pairs, layout.crossings.size, layout.edges.sumOf { edge -> edge.segments.sumOf { it.length } })
    }

    private fun render(layout: UnderstandingGraphLayout, output: Path) {
        val zoom = min(1.0, min(3000.0 / layout.bounds.width, 3000.0 / layout.bounds.height))
        val width = ceil(layout.bounds.width * zoom).toInt().coerceAtLeast(1)
        val height = ceil(layout.bounds.height * zoom).toInt().coerceAtLeast(1)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            DiagramCanvasPainter.paint(graphics, DiagramPaintState(layout, emptyList(), width, height, zoom,
                layout.bounds.centerX, layout.bounds.centerY, null, null, null, emptyMap(), emptyMap(),
                layout.nodes.values.filter { "start" in it.node.flowRoles }.mapTo(mutableSetOf()) { it.node.id },
                layout.nodes.values.filter { "end" in it.node.flowRoles }.mapTo(mutableSetOf()) { it.node.id },
                emptySet(), emptySet(), 0f, null, false, 0f, 0))
        } finally { graphics.dispose() }
        ImageIO.write(image, "png", output.toFile())
    }

    /** Merge consumers or payment coordination responsibilities, retaining independent read requests. */
    private fun regroup(name: String, groups: List<DiagramCluster>): List<DiagramCluster> {
        val mergedIds = when (name) {
            "article-publish" -> setOf("search", "author-statistics", "activity", "sitemap")
            "paid-article" -> setOf("prepare", "email-channel", "wechat-channel", "confirmation", "callbacks")
            else -> setOf("group-1", "group-2", "group-3")
        }
        val merged = DiagramCluster("merged-stage", when (name) {
            "article-publish" -> "发布后的索引、统计与站点更新"
            "paid-article" -> "支付协调：双渠道、确认与回调"
            else -> "并列处理分支"
        }, groups.filter { it.id in mergedIds }.flatMapTo(linkedSetOf()) { it.nodeIds })
        val first = groups.indexOfFirst { it.id in mergedIds }
        return groups.take(first) + merged + groups.drop(first).filterNot { it.id in mergedIds }
    }

    private fun denseGraph(): Pair<UnderstandingGraph, List<DiagramCluster>> {
        val nodes = (0 until 5).flatMap { group -> (0 until 4).map { index -> GraphNode("g$group-$index", "阶段 $group · 步骤 $index") } }
        val local = (0 until 5).flatMap { group -> (0 until 3).map { index ->
            GraphEdge("local-$group-$index", "g$group-$index", "g$group-${index + 1}", "call")
        } }
        val external = (1 until 4).flatMap { group -> listOf(
            GraphEdge("dispatch-$group", "g0-2", "g$group-0", "call"),
            GraphEdge("parallel-$group", "g0-2", "g$group-0", "data_flow"),
            GraphEdge("join-$group", "g$group-3", "g4-0", "data_flow"),
        ) } + GraphEdge("retry", "g4-3", "g0-1", "call")
        val groups = (0 until 5).map { group -> DiagramCluster("group-$group", "处理阶段 $group",
            (0 until 4).mapTo(linkedSetOf()) { "g$group-$it" }) }
        return UnderstandingGraph(nodes, local + external) to groups
    }

    private data class RoutingQuality(val contentConflicts: Int, val laneConflictPairs: List<String>, val crossings: Int, val edgeLength: Double)
    private data class RoutingComparison(val sample: String, val direction: String, val version: String, val nodeCount: Int,
        val edgeCount: Int, val layoutMs: Double, val width: Double, val height: Double, val quality: RoutingQuality)
}
