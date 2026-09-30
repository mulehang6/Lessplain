package com.master.understanding.diagram

import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.UnderstandingGraph
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** Run with `-PgraphBenchmark`; timings are recorded, never used as assertions. */
@Tag("benchmark")
class GraphPerformanceBenchmarkTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `measure layout paint and retained memory at three graph sizes`() {
        val report = buildString {
            appendLine("OS=${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("Java=${System.getProperty("java.version")}, processors=${Runtime.getRuntime().availableProcessors()}")
            appendLine("Viewport=1000x700, warmup=2x100 nodes, layout samples=3, paint samples=30")
            repeat(2) { layoutUnderstandingGraph(graph(100), LayoutDirection.VERTICAL) }
            for (size in listOf(100, 500, 1000)) {
                val graph = graph(size)
                System.gc()
                val before = usedMemory()
                val layoutSamples = mutableListOf<Double>()
                var layout: UnderstandingGraphLayout? = null
                repeat(3) {
                    val start = System.nanoTime()
                    layout = layoutUnderstandingGraph(graph, LayoutDirection.VERTICAL)
                    layoutSamples += (System.nanoTime() - start) / 1_000_000.0
                }
                val result = requireNotNull(layout)
                val image = BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB)
                val middle = result.nodes.getValue("n${size / 2}").bounds
                val state = DiagramPaintState(result, emptyList(), 1000, 700, 1.0,
                    middle.centerX, middle.centerY, "n${size / 2}", null, null,
                    emptyMap(), emptyMap(), emptySet(), emptySet(), emptySet(), emptySet(),
                    0f, null, false, 0f, 32)
                repeat(5) { image.createGraphics().let { g -> DiagramCanvasPainter.paint(g, state); g.dispose() } }
                val paintSamples = mutableListOf<Double>()
                repeat(30) {
                    val g = image.createGraphics()
                    val start = System.nanoTime()
                    DiagramCanvasPainter.paint(g, state)
                    paintSamples += (System.nanoTime() - start) / 1_000_000.0
                    g.dispose()
                }
                System.gc()
                val after = usedMemory()
                appendLine("nodes=$size edges=${graph.edges.size} layoutMedianMs=${layoutSamples.sorted()[1]} " +
                    "paintP95Ms=${percentile(paintSamples, 0.95)} retainedMemoryDeltaBytes=${after - before}")
            }
        }
        val output = Path.of("build", "reports", "graph-benchmark.txt")
        Files.createDirectories(output.parent)
        Files.writeString(output, report)
        println(report)
    }

    private fun graph(size: Int): UnderstandingGraph = UnderstandingGraph(
        (0 until size).map { GraphNode("n$it", "步骤 $it：读取参数，执行判断") },
        (0 until size).flatMap { from ->
            (1..3).mapNotNull { distance ->
                val to = from + distance
                if (to >= size) null else GraphEdge("e$from-$distance", "n$from", "n$to",
                    "control_flow", if (distance == 1) "继续" else null)
            }
        },
    )

    private fun percentile(values: List<Double>, rank: Double): Double =
        values.sorted()[(ceil(values.size * rank).toInt() - 1).coerceIn(values.indices)]

    private fun usedMemory(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
