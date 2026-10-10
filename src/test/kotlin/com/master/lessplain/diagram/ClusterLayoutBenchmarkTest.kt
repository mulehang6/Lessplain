package com.master.lessplain.diagram

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Captures ordinary and internal grouped-flow geometry and timings without fragile wall-clock assertions. */
@Tag("benchmark")
class ClusterLayoutBenchmarkTest {
    @Test
    fun `measure real grouped flows in both directions`() {
        val report = buildString {
            for (fixture in listOf("article-publish-clusters", "paid-article-clusters")) {
                val (graph, groups) = groupedFlowFixture(fixture)
                appendLine("$fixture nodes=${graph.nodes.size} edges=${graph.edges.size} groups=${groups.size}")
                for (direction in LayoutDirection.entries) {
                    layoutUnderstandingClusters(graph, groups, direction = direction)
                    val times = mutableListOf<Double>()
                    lateinit var layout: UnderstandingGraphLayout
                    repeat(3) {
                        val start = System.nanoTime()
                        layout = layoutUnderstandingClusters(graph, groups, direction = direction)
                        times += (System.nanoTime() - start) / 1_000_000.0
                    }
                    appendLine("$direction medianMs=${times.sorted()[1]} width=${layout.bounds.width} height=${layout.bounds.height}")
                    layout.clusters.forEach { appendLine("  ${it.id}: ${it.bounds.width}x${it.bounds.height}") }
                    layoutUnderstandingGraph(graph, direction)
                    val ordinaryTimes = mutableListOf<Double>()
                    lateinit var plain: UnderstandingGraphLayout
                    repeat(3) {
                        val start = System.nanoTime()
                        plain = layoutUnderstandingGraph(graph, direction)
                        ordinaryTimes += (System.nanoTime() - start) / 1_000_000.0
                    }
                    appendLine("ordinary $direction medianMs=${ordinaryTimes.sorted()[1]} width=${plain.bounds.width} height=${plain.bounds.height}")
                    val semantic = semanticSampleFixture(fixture)
                    layoutUnderstandingGraph(semantic, direction)
                    val semanticTimes = mutableListOf<Double>()
                    repeat(3) {
                        val start = System.nanoTime()
                        plain = layoutUnderstandingGraph(semantic, direction)
                        semanticTimes += (System.nanoTime() - start) / 1_000_000.0
                    }
                    appendLine("semantic $direction medianMs=${semanticTimes.sorted()[1]} width=${plain.bounds.width} height=${plain.bounds.height}")
                }
            }
        }
        val output = Path.of("build", "reports", "cluster-layout-benchmark.txt")
        Files.createDirectories(output.parent)
        Files.writeString(output, report)
        println(report)
    }
}
