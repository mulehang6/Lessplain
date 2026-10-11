package com.lessplain.diagram

import com.intellij.ui.JBColor
import com.lessplain.model.GraphEdge
import com.lessplain.model.GraphNode
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import java.awt.event.ComponentEvent
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.geom.PathIterator
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.awt.Toolkit
import com.lessplain.ide.GraphLayoutPreference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UnderstandingGraphLayoutTest {
    private val branch = UnderstandingGraph(
        nodes = listOf(
            GraphNode("start", "入口"),
            GraphNode("yes", "通过"),
            GraphNode("no", "拒绝"),
            GraphNode("end", "结束"),
        ),
        edges = listOf(
            GraphEdge("yes-edge", "start", "yes", "control_flow", "有效"),
            GraphEdge("no-edge", "start", "no", "control_flow", "无效"),
            GraphEdge("yes-end", "yes", "end", "control_flow"),
            GraphEdge("no-end", "no", "end", "control_flow"),
        ),
    )

    @Test
    fun `dragged node reroutes edges and automatic arrangement restores original geometry`() {
        val original = layoutUnderstandingGraph(branch)
        val moved = positionUnderstandingNodes(branch, original,
            mapOf("yes" to Point2D.Double(470.0, 490.0)))
        assertEquals(470.0, moved.nodes.getValue("yes").bounds.x)
        assertEquals(490.0, moved.nodes.getValue("yes").bounds.y)
        assertNotEquals(original.edges.first().arrowX, moved.edges.first().arrowX)
        moved.edges.forEach { assertPathWithinBounds(moved, it) }
        assertEquals(original, positionUnderstandingNodes(branch, original, emptyMap()))
    }

    @Test
    fun `dragging a node saves its position and arrange control clears it`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("drag", 1, null, null, "测试", "", null, branch, 0)
            val diagram = createUnderstandingDiagram(record)
            try {
                diagram.setSize(900, 600)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                diagram.doLayout()
                diagram.zoom = 1.5
                val updates = mutableListOf<Map<String, com.lessplain.ide.GraphNodePosition>>()
                diagram.onNodePositionsChanged(updates::add)
                val node = diagram.layout.nodes.getValue("start").bounds
                val x = (diagram.width / 2 + (node.centerX - diagram.center.x) * diagram.zoom).toInt()
                val y = (diagram.height / 2 + (node.centerY - diagram.center.y) * diagram.zoom).toInt()
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_PRESSED, 0, 0, x, y,
                    1, false, MouseEvent.BUTTON1))
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_DRAGGED, 0,
                    MouseEvent.BUTTON1_DOWN_MASK, x + 50, y + 30, 0, false, MouseEvent.BUTTON1))
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_RELEASED, 0, 0,
                    x + 50, y + 30, 1, false, MouseEvent.BUTTON1))
                assertTrue(updates.last().containsKey("start"))
                assertEquals(node.x + 50 / 1.5, updates.last().getValue("start").x, 1.0)
                val arrange = diagram.components.filterIsInstance<UnderstandingDiagramControls>().single()
                    .components.filterIsInstance<JButton>().single { it.toolTipText == "一键整理节点" }
                arrange.doClick()
                assertTrue(updates.last().isEmpty())
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `branch joins retain separate nodes and complete routed paths`() {
        val layout = layoutUnderstandingGraph(branch)
        assertTrue(layout.nodes.getValue("start").bounds.y < layout.nodes.getValue("yes").bounds.y)
        assertTrue(layout.nodes.getValue("yes").bounds.y < layout.nodes.getValue("end").bounds.y)
        assertFalse(layout.nodes.getValue("yes").bounds.intersects(layout.nodes.getValue("no").bounds))

        layout.edges.forEach { edge ->
            val coordinates = DoubleArray(6)
            val iterator = edge.path.getPathIterator(null)
            assertEquals(PathIterator.SEG_MOVETO, iterator.currentSegment(coordinates))
            val source = layout.nodes.getValue(edge.edge.from).bounds
            assertTrue(coordinates[0] in source.minX..source.maxX)
            val target = layout.nodes.getValue(edge.edge.to).bounds
            assertTrue(edge.arrowX >= target.minX - 0.1 && edge.arrowX <= target.maxX + 0.1)
            assertTrue(edge.arrowY >= target.minY - 0.1 && edge.arrowY <= target.maxY + 0.1)
            assertPathWithinBounds(layout, edge)
        }
    }

    @Test
    fun `cycle and disconnected node retain visible finite geometry`() {
        val graph = UnderstandingGraph(
            nodes = listOf(GraphNode("a", "A"), GraphNode("b", "B"), GraphNode("free", "独立")),
            edges = listOf(
                GraphEdge("ab", "a", "b", "control_flow"),
                GraphEdge("ba", "b", "a", "control_flow"),
                GraphEdge("self", "b", "b", "call"),
            ),
        )
        val layout = layoutUnderstandingGraph(graph)
        assertEquals(3, layout.nodes.size)
        assertEquals(3, layout.edges.size)
        layout.edges.forEach { assertPathWithinBounds(layout, it) }
        assertTrue(layout.bounds.width.isFinite() && layout.bounds.height.isFinite())
    }

    @Test
    fun `mixed call and control flow edges keep each operation in reading order`() {
        val graph = UnderstandingGraph(
            nodes = listOf(GraphNode("input", "读取参数"), GraphNode("lookup", "查找用户"), GraphNode("check", "校验密码")),
            edges = listOf(
                GraphEdge("call", "input", "lookup", "call"),
                GraphEdge("flow", "lookup", "check", "control_flow"),
            ),
        )
        val layout = layoutUnderstandingGraph(graph)
        assertTrue(layout.nodes.getValue("input").bounds.y < layout.nodes.getValue("lookup").bounds.y)
        assertTrue(layout.nodes.getValue("lookup").bounds.y < layout.nodes.getValue("check").bounds.y)
        assertTrue(layout.edges.all { it.path.bounds2D.width.isFinite() })
    }

    @Test
    fun `unconnected question clusters use rows instead of one long horizontal line`() {
        val graph = UnderstandingGraph((1..5).map { GraphNode("q$it", "问题 $it", "question") }, emptyList())
        val layout = layoutUnderstandingGraph(graph)
        assertEquals(layout.nodes.getValue("q1").bounds.y, layout.nodes.getValue("q2").bounds.y)
        assertTrue(layout.nodes.getValue("q3").bounds.y > layout.nodes.getValue("q1").bounds.y)
        assertTrue(layout.bounds.height > layout.bounds.width / 2)
    }

    @Test
    fun `question clusters share a canvas without clipping their nodes`() {
        val graph = UnderstandingGraph(
            listOf(GraphNode("a1", "A 开始"), GraphNode("a2", "A 结束"),
                GraphNode("b1", "B 开始"), GraphNode("b2", "B 结束")),
            listOf(GraphEdge("a", "a1", "a2", "data_flow"), GraphEdge("b", "b1", "b2", "data_flow")),
        )
        val layout = layoutUnderstandingClusters(graph, listOf(
            DiagramCluster("A", "第一个追问", setOf("a1", "a2")),
            DiagramCluster("B", "第二个追问", setOf("b1", "b2")),
        ))
        assertEquals(2, layout.clusters.size)
        assertTrue(layout.clusters[0].bounds.maxY < layout.clusters[1].bounds.minY)
        layout.nodes.values.forEach { node ->
            assertTrue(layout.clusters.any { it.bounds.contains(node.bounds) })
        }
        layout.edges.forEach { assertPathWithinBounds(layout, it) }
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("two-clusters", 1, null, null, "追问", "", null, graph, 0)
            val diagram = createUnderstandingDiagram(record, clusters = listOf(
                DiagramCluster("A", "第一个追问", setOf("a1", "a2")),
                DiagramCluster("B", "第二个追问", setOf("b1", "b2")),
            ))
            try {
                diagram.setSize(850, 800)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                diagram.doLayout()
                val controls = diagram.components.filterIsInstance<UnderstandingDiagramControls>().single()
                assertEquals(4, controls.components.filterIsInstance<JButton>().count { it.isVisible })
                assertEquals(2, controls.sideControls.components.filterIsInstance<JButton>().count { it.isVisible })
                diagram.focusCluster("B")
                assertEquals(diagram.layout.nodes.getValue("b1").bounds.centerY, diagram.center.y, 0.001)
                diagram.fitContent()
                assertEquals(layout.bounds.centerY, diagram.center.y, 0.001)
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `dragging a node inside a cluster moves only that node and expands its boundary`() {
        SwingUtilities.invokeAndWait {
            val graph = UnderstandingGraph(
                listOf(GraphNode("a1", "开始"), GraphNode("a2", "结束"), GraphNode("b1", "其他追问")),
                listOf(GraphEdge("a-edge", "a1", "a2", "data_flow")),
            )
            val specs = listOf(
                DiagramCluster("A", "第一个追问", setOf("a1", "a2")),
                DiagramCluster("B", "第二个追问", setOf("b1")),
            )
            val base = layoutUnderstandingClusters(graph, specs)
            val record = UnderstandingRecord("drag-cluster", 1, null, null, "追问", "", null, graph, 0)
            val diagram = createUnderstandingDiagram(record, clusters = specs)
            try {
                diagram.setSize(1000, 850)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                diagram.doLayout()
                diagram.zoom = 0.8
                val updates = mutableListOf<Map<String, com.lessplain.ide.GraphNodePosition>>()
                diagram.onNodePositionsChanged(updates::add)
                val node = base.nodes.getValue("a1").bounds
                val pressX = (diagram.width / 2 + (node.x - diagram.center.x) * diagram.zoom).toInt() - 5
                val pressY = (diagram.height / 2 + (node.centerY - diagram.center.y) * diagram.zoom).toInt()
                val deltaX = ((base.clusters.first().bounds.maxX + 180 - node.centerX) * diagram.zoom).toInt()
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_PRESSED, 0, 0,
                    pressX, pressY, 1, false, MouseEvent.BUTTON1))
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_DRAGGED, 0,
                    MouseEvent.BUTTON1_DOWN_MASK, pressX + deltaX, pressY, 0, false, MouseEvent.BUTTON1))
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_RELEASED, 0, 0,
                    pressX + deltaX, pressY, 1, false, MouseEvent.BUTTON1))
                assertEquals(setOf("a1"), updates.single().keys)
                val moved = positionUnderstandingNodes(graph, base, updates.single().mapValues { (_, p) ->
                    Point2D.Double(p.x, p.y)
                })
                assertEquals(base.nodes.getValue("a2").bounds, moved.nodes.getValue("a2").bounds)
                assertEquals(base.clusters[1].bounds, moved.clusters[1].bounds)
                assertTrue(moved.nodes.getValue("a1").bounds.x > base.clusters.first().bounds.maxX)
                assertTrue(moved.clusters.first().bounds.contains(moved.nodes.getValue("a1").bounds))
                assertTrue(moved.clusters.first().bounds.maxX > base.clusters.first().bounds.maxX)
                assertTrue(moved.clusters.first().bounds.contains(moved.edges.single().path.bounds2D))
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `cluster boundary follows a dragged node on every side`() {
        val graph = UnderstandingGraph(listOf(GraphNode("node", "节点")), emptyList())
        val base = layoutUnderstandingClusters(graph, listOf(DiagramCluster("q", "追问", setOf("node"))))
        val original = base.clusters.single().bounds
        val node = base.nodes.getValue("node").bounds
        val destinations = listOf(
            Point2D.Double(original.minX - node.width - 100, node.y),
            Point2D.Double(original.maxX + 100, node.y),
            Point2D.Double(node.x, original.minY - node.height - 100),
            Point2D.Double(node.x, original.maxY + 100),
        )
        destinations.forEach { destination ->
            val moved = positionUnderstandingNodes(graph, base, mapOf("node" to destination))
            val boundary = moved.clusters.single().bounds
            assertTrue(boundary.contains(moved.nodes.getValue("node").bounds))
            assertTrue(boundary.contains(DiagramNodePainter.copyBounds(moved.nodes.getValue("node").bounds)))
        }
        assertTrue(positionUnderstandingNodes(graph, base, mapOf("node" to destinations[0]))
            .clusters.single().bounds.minX < original.minX)
        assertTrue(positionUnderstandingNodes(graph, base, mapOf("node" to destinations[1]))
            .clusters.single().bounds.maxX > original.maxX)
        assertTrue(positionUnderstandingNodes(graph, base, mapOf("node" to destinations[2]))
            .clusters.single().bounds.minY < original.minY)
        assertTrue(positionUnderstandingNodes(graph, base, mapOf("node" to destinations[3]))
            .clusters.single().bounds.maxY > original.maxY)
    }

    @Test
    fun `request example shows its full cluster beside a property panel`() {
        SwingUtilities.invokeAndWait {
            val graph = UnderstandingGraph(
                listOf(GraphNode("request", "示例请求\nusername=alice"), GraphNode("read", "读取 username"),
                    GraphNode("value", "得到 alice"), GraphNode("missing", "得到 null"), GraphNode("assign", "赋值")),
                listOf(GraphEdge("a", "request", "read", "data_flow"),
                    GraphEdge("b", "read", "value", "control_flow", "存在"),
                    GraphEdge("c", "read", "missing", "control_flow", "缺失"),
                    GraphEdge("d", "value", "assign", "data_flow")),
            )
            val record = UnderstandingRecord("example-cluster", 1, null, null, "追问", "", null, graph, 0)
            val cluster = DiagramCluster("q", "getParameter(\"username\") 是什么意思？", graph.nodes.mapTo(mutableSetOf()) { it.id })
            val panel = DiagramExamplePanel("q", "username", "alice", setOf("missing"), mapOf(
                "request" to "parameter", "read" to "parameter", "value" to "result",
                "missing" to "result", "assign" to "variable",
            ))
            val diagram = createUnderstandingDiagram(record, clusters = listOf(cluster), examplePanels = listOf(panel))
            val previewMode = System.getenv("UNDERSTANDING_RENDER_PREVIEW")
            val previousBright = JBColor.isBright()
            try {
                if (previewMode == "question-canvas-dark") JBColor.setDark(true)
                diagram.setSize(850, 900)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                diagram.doLayout()
                val layout = layoutUnderstandingClusters(graph, listOf(cluster), listOf(panel))
                val node = layout.nodes.getValue("request").bounds
                val panelBounds = layout.clusters.single().exampleBounds!!
                assertTrue(layout.clusters.single().bounds.contains(panelBounds))
                assertTrue(layout.nodes.values.none { it.bounds.intersects(panelBounds) })
                val hoverX = (diagram.width / 2 + (node.centerX - diagram.center.x) * diagram.zoom).toInt()
                val hoverY = (diagram.height / 2 + (node.centerY - diagram.center.y) * diagram.zoom).toInt()
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_MOVED, 0, 0, hoverX, hoverY, 0, false))
                val image = BufferedImage(850, 900, BufferedImage.TYPE_INT_ARGB)
                diagram.paint(image.createGraphics())
                assertTrue(diagram.zoom > 0.0)
                diagram.selectUnderstandingNode("value")
                val focusedResult = BufferedImage(850, 900, BufferedImage.TYPE_INT_ARGB)
                diagram.paint(focusedResult.createGraphics())
                assertTrue(diagram.zoom > 0.0)
                if (previewMode == "question-canvas" || previewMode == "question-canvas-dark") {
                    val name = if (previewMode == "question-canvas-dark") "question-canvas-preview-dark.png" else "question-canvas-preview.png"
                    val preview = Path.of("build", "reports", name)
                    Files.createDirectories(preview.parent)
                    ImageIO.write(image, "png", preview.toFile())
                }
            } finally {
                if (previewMode == "question-canvas-dark") JBColor.setDark(!previousBright)
                diagram.dispose()
            }
        }
    }

    @Test
    fun `example property panel can be dragged independently and restored inside its cluster`() {
        SwingUtilities.invokeAndWait {
            val graph = UnderstandingGraph(
                listOf(GraphNode("request", "请求"), GraphNode("read", "读取参数")),
                listOf(GraphEdge("flow", "request", "read", "data_flow")),
            )
            val record = UnderstandingRecord("panel-drag", 1, null, null, "追问", "", null, graph, 0)
            val cluster = DiagramCluster("question", "读取 username", setOf("request", "read"))
            val panel = DiagramExamplePanel("question", "username", "alice", emptySet())
            val base = layoutUnderstandingClusters(graph, listOf(cluster), listOf(panel))
            val diagram = createUnderstandingDiagram(record, clusters = listOf(cluster), examplePanels = listOf(panel))
            try {
                diagram.setSize(1000, 850)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                diagram.doLayout()
                diagram.zoom = 0.8
                val updates = mutableListOf<Map<String, com.lessplain.ide.GraphNodePosition>>()
                diagram.onNodePositionsChanged(updates::add)
                val initialPanel = base.clusters.single().exampleBounds!!
                val pressX = (diagram.width / 2 + (initialPanel.centerX - diagram.center.x) * diagram.zoom).toInt()
                val pressY = (diagram.height / 2 + (initialPanel.centerY - diagram.center.y) * diagram.zoom).toInt()
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_PRESSED, 0, 0,
                    pressX, pressY, 1, false, MouseEvent.BUTTON1))
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_DRAGGED, 0,
                    MouseEvent.BUTTON1_DOWN_MASK, pressX + 180, pressY, 0, false, MouseEvent.BUTTON1))
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_RELEASED, 0, 0,
                    pressX + 180, pressY, 1, false, MouseEvent.BUTTON1))
                val saved = updates.single()
                assertEquals(setOf(examplePositionKey("question")), saved.keys)
                val moved = diagram.clusters.single()
                assertEquals(initialPanel.x + 180 / diagram.zoom, moved.exampleBounds!!.x, 1.0)
                assertTrue(moved.bounds.maxX > base.clusters.single().bounds.maxX)
                assertTrue(moved.bounds.contains(moved.exampleBounds))
                assertEquals(base.nodes.getValue("request").bounds, positionUnderstandingNodes(graph, base,
                    saved.mapValues { (_, p) -> Point2D.Double(p.x, p.y) }).nodes.getValue("request").bounds)

                val restored = createUnderstandingDiagram(record, clusters = listOf(cluster), examplePanels = listOf(panel))
                try {
                    restored.setSize(850, 900)
                    awaitLayout(restored)
                    restored.setNodePositions(saved)
                    assertEquals(moved.exampleBounds, restored.clusters.single().exampleBounds)
                    restored.arrangeNodes()
                    assertEquals(initialPanel, restored.clusters.single().exampleBounds)
                } finally { restored.dispose() }
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `login flow layout remains layered with call edges and two failure branches`() {
        val graph = UnderstandingGraph(
            nodes = listOf(
                GraphNode("input", "读取用户名与密码"), GraphNode("lookup", "查找用户"),
                GraphNode("password", "校验密码"), GraphNode("prepare", "准备用户"),
                GraphNode("session", "生成会话"), GraphNode("response", "写入 Cookie"),
                GraphNode("no-user", "用户不存在"), GraphNode("wrong-password", "密码错误"),
            ),
            edges = listOf(
                GraphEdge("a", "input", "lookup", "call"),
                GraphEdge("b", "lookup", "password", "control_flow", "找到用户"),
                GraphEdge("c", "lookup", "no-user", "control_flow", "用户不存在"),
                GraphEdge("d", "password", "prepare", "control_flow", "密码正确"),
                GraphEdge("e", "password", "wrong-password", "control_flow", "密码错误"),
                GraphEdge("f", "prepare", "session", "call"),
                GraphEdge("g", "session", "response", "control_flow"),
            ),
        )
        val layout = layoutUnderstandingGraph(graph)
        assertTrue(layout.nodes.getValue("input").bounds.y < layout.nodes.getValue("lookup").bounds.y)
        assertTrue(layout.nodes.getValue("lookup").bounds.y < layout.nodes.getValue("password").bounds.y)
        assertTrue(layout.nodes.getValue("password").bounds.y < layout.nodes.getValue("response").bounds.y)
        layout.nodes.values.forEach { left ->
            layout.nodes.values.filter { it !== left }.forEach { right ->
                assertFalse(left.bounds.intersects(right.bounds))
            }
        }
        if (System.getenv("UNDERSTANDING_RENDER_PREVIEW") == "login") {
            SwingUtilities.invokeAndWait {
                val record = UnderstandingRecord("login", 1, null, null, "登录", "", null, graph, 0)
                val diagram = createUnderstandingDiagram(record)
                try {
                    diagram.setSize(1000, 740)
                    diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                    awaitLayout(diagram)
                    val image = BufferedImage(1000, 740, BufferedImage.TYPE_INT_ARGB)
                    diagram.paint(image.createGraphics())
                    val preview = Path.of("build", "reports", "login-layout-preview.png")
                    Files.createDirectories(preview.parent)
                    ImageIO.write(image, "png", preview.toFile())
                } finally {
                    diagram.dispose()
                }
            }
        }
    }

    @Test
    fun `parameter example keeps input action and two outcomes distinct`() {
        val graph = UnderstandingGraph(
            nodes = listOf(
                GraphNode("request", "假设请求参数\nusername=alice", "example"),
                GraphNode("read", "getParameter(\"username\")\n按名称读取"),
                GraphNode("value", "得到字符串\n\"alice\"", "example"),
                GraphNode("missing", "没带 username\n得到 null", "example"),
                GraphNode("assign", "赋给局部变量\nusername = \"alice\"", "example"),
            ),
            edges = listOf(
                GraphEdge("request-read", "request", "read", "data_flow"),
                GraphEdge("read-value", "read", "value", "control_flow", "带 username 参数"),
                GraphEdge("read-missing", "read", "missing", "control_flow", "没带 username 参数"),
                GraphEdge("value-assign", "value", "assign", "data_flow"),
            ),
        )
        val layout = layoutUnderstandingGraph(graph)
        assertTrue(layout.nodes.getValue("request").bounds.y < layout.nodes.getValue("read").bounds.y)
        assertEquals(layout.nodes.getValue("value").bounds.y, layout.nodes.getValue("missing").bounds.y)
        val previewMode = System.getenv("UNDERSTANDING_RENDER_PREVIEW")
        if (previewMode == "example" || previewMode == "example-dark") {
            SwingUtilities.invokeAndWait {
                val record = UnderstandingRecord("example", 1, null, null, "示例", "", null, graph, 0)
                val diagram = createUnderstandingDiagram(record)
                val previousBright = JBColor.isBright()
                try {
                    if (previewMode == "example-dark") JBColor.setDark(true)
                    diagram.setSize(850, 650)
                    diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                    awaitLayout(diagram)
                    diagram.fitContent()
                    val image = BufferedImage(850, 650, BufferedImage.TYPE_INT_ARGB)
                    diagram.paint(image.createGraphics())
                    val name = if (previewMode == "example-dark") "parameter-example-preview-dark.png" else "parameter-example-preview.png"
                    val preview = Path.of("build", "reports", name)
                    Files.createDirectories(preview.parent)
                    ImageIO.write(image, "png", preview.toFile())
                } finally {
                    if (previewMode == "example-dark") JBColor.setDark(!previousBright)
                    diagram.dispose()
                }
            }
        }
    }

    @Test
    fun `diagram fits the initial viewport and centers the active node`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("record", 1, null, null, "测试", "回答", null, branch, 0)
            val diagram = createUnderstandingDiagram(record)
            val previewMode = System.getenv("UNDERSTANDING_RENDER_PREVIEW")
            val previousBright = JBColor.isBright()
            try {
                if (previewMode == "dark") JBColor.setDark(true)
                diagram.setSize(900, 600)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                diagram.doLayout()
                assertTrue(diagram.zoom > 0.0)
                assertEquals(diagram.layout.nodes.getValue("start").bounds.centerX, diagram.center.x, 0.001)
                diagram.showUnderstandingPath(listOf("yes-edge"), "yes", centerNode = true)
                assertEquals(diagram.layout.nodes.getValue("yes").bounds.centerX, diagram.center.x, 0.001)
                assertEquals(diagram.layout.nodes.getValue("yes").bounds.centerY, diagram.center.y, 0.001)
                val image = BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB)
                diagram.paint(image.createGraphics())
                assertNotEquals(image.getRGB(450, 300), image.getRGB(0, 0))
                repeat(5) { diagram.advanceFlow() }
                val nextFrame = BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB)
                diagram.paint(nextFrame.createGraphics())
                assertTrue((0 until 900).any { x -> (0 until 600).any { y -> image.getRGB(x, y) != nextFrame.getRGB(x, y) } })
                if (previewMode == "1" || previewMode == "dark") {
                    val name = if (previewMode == "dark") "understanding-graph-preview-dark.png" else "understanding-graph-preview.png"
                    val preview = Path.of("build", "reports", name)
                    Files.createDirectories(preview.parent)
                    ImageIO.write(image, "png", preview.toFile())
                }
                diagram.flowEnabled = false
                assertFalse(diagram.flowEnabled)
            } finally {
                if (previewMode == "dark") JBColor.setDark(!previousBright)
                diagram.dispose()
            }
        }
    }

    @Test
    fun `camera eases between nodes and retargets from the visible position`() {
        val motion = UnderstandingCameraMotion(0.0, 0.0)
        motion.retarget(0.0, 0.0, 100.0, 200.0, 1_000L)
        val halfway = motion.positionAt(1_000L + UnderstandingCameraMotion.DURATION_NANOS / 2)
        assertTrue(halfway.x in 0.0..100.0)
        assertTrue(halfway.y in 0.0..200.0)
        assertFalse(motion.isFinished(1_000L + UnderstandingCameraMotion.DURATION_NANOS / 2))
        motion.retarget(halfway.x, halfway.y, -50.0, 50.0, 2_000L)
        assertEquals(halfway.x, motion.positionAt(2_000L).x, 0.05)
        val end = motion.positionAt(2_000L + UnderstandingCameraMotion.DURATION_NANOS)
        assertEquals(-50.0, end.x, 0.05)
        assertEquals(50.0, end.y, 0.05)
    }

    @Test
    fun `graph controls wrap and flow toggle updates without pointer movement`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("controls", 1, null, null, "测试", "回答", null, branch, 0)
            val diagram = createUnderstandingDiagram(record)
            try {
                diagram.setSize(120, 500)
                diagram.doLayout()
                val controls = diagram.components.filterIsInstance<UnderstandingDiagramControls>().single()
                assertTrue(controls.x + controls.width <= diagram.width)
                val buttons = controls.components.filterIsInstance<JButton>().filter { it.isVisible }
                assertEquals(4, buttons.size)
                assertTrue(buttons.maxOf { it.y } > buttons.minOf { it.y })
                val side = controls.sideControls
                assertTrue(side.x + side.width <= diagram.width)
                assertEquals(1, side.components.filterIsInstance<JButton>().count { it.isVisible })
                diagram.flowEnabled = false
                assertFalse(diagram.flowEnabled)
            } finally {
                diagram.dispose()
            }
        }
    }

    @Test
    fun `hover question action opens its own layer instead of selecting the source node`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("questions", 1, null, null, "测试", "", null, branch, 0)
            val diagram = createUnderstandingDiagram(record)
            try {
                diagram.setSize(900, 600)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                diagram.setQuestionCounts(mapOf("start" to 2))
                var badgeNode: String? = null
                var copiedNode: String? = null
                var selectedNode: String? = null
                diagram.onQuestionBadgeClicked { badgeNode = it }
                diagram.onCopyNodeContext { copiedNode = it }
                diagram.onNodeSelected { selectedNode = it }
                val node = diagram.layout.nodes.getValue("start").bounds
                val hoverX = (diagram.width / 2 + (node.centerX - diagram.center.x) * diagram.zoom).toInt()
                val hoverY = (diagram.height / 2 + (node.centerY - diagram.center.y) * diagram.zoom).toInt()
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_MOVED, 0, 0, hoverX, hoverY, 0, false))
                val action = DiagramNodePainter.questionBounds(node)
                val x = (diagram.width / 2 + (action.centerX - diagram.center.x) * diagram.zoom).toInt()
                val y = (diagram.height / 2 + (action.centerY - diagram.center.y) * diagram.zoom).toInt()
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
                assertEquals("start", badgeNode)
                assertEquals(null, selectedNode)
                val copyAction = DiagramNodePainter.copyBounds(node)
                val copyX = (diagram.width / 2 + (copyAction.centerX - diagram.center.x) * diagram.zoom).toInt()
                val copyY = (diagram.height / 2 + (copyAction.centerY - diagram.center.y) * diagram.zoom).toInt()
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_PRESSED, 0, 0, copyX, copyY, 1, false, MouseEvent.BUTTON1))
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_RELEASED, 0, 0, copyX, copyY, 1, false, MouseEvent.BUTTON1))
                assertEquals("start", copiedNode)
                diagram.showCopied("start")
                assertEquals("复制", diagram.getToolTipText(
                    MouseEvent(diagram, MouseEvent.MOUSE_MOVED, 0, 0, copyX, copyY, 0, false)))
                copiedNode = null
                diagram.actionMap.get("copy-node").actionPerformed(ActionEvent(diagram, ActionEvent.ACTION_PERFORMED, "copy-node"))
                assertEquals("start", copiedNode)
            } finally {
                diagram.dispose()
            }
        }
    }

    @Test
    fun `node hover extends beyond the border and action tips vanish on exit at different zooms`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("hover", 1, null, null, "测试", "", null, branch, 0)
            val diagram = createUnderstandingDiagram(record)
            try {
                diagram.setSize(900, 600)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                diagram.setQuestionCounts(mapOf("start" to 1))
                val node = diagram.layout.nodes.getValue("start").bounds
                val copy = DiagramNodePainter.copyBounds(node)
                val questions = DiagramNodePainter.questionBounds(node)
                assertTrue(copy.y > node.maxY)
                assertTrue(questions.y > node.maxY)
                assertTrue(copy.maxX < questions.x)
                for (scale in listOf(0.6, 1.5)) {
                    diagram.zoom = scale
                    fun screenX(worldX: Double) = (diagram.width / 2 + (worldX - diagram.center.x) * scale).toInt()
                    fun screenY(worldY: Double) = (diagram.height / 2 + (worldY - diagram.center.y) * scale).toInt()
                    fun move(x: Int, y: Int) = diagram.dispatchEvent(
                        MouseEvent(diagram, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false))
                    val nearX = screenX(node.x) - 5
                    val nearY = screenY(node.centerY)
                    move(nearX, nearY)
                    assertEquals(null, diagram.getToolTipText(
                        MouseEvent(diagram, MouseEvent.MOUSE_MOVED, 0, 0, nearX, nearY, 0, false)))
                    val copyX = screenX(copy.centerX)
                    val copyY = screenY(copy.centerY)
                    move(copyX, copyY)
                    assertEquals("复制", diagram.getToolTipText(
                        MouseEvent(diagram, MouseEvent.MOUSE_MOVED, 0, 0, copyX, copyY, 0, false)))
                    move(0, 500)
                    assertEquals(null, diagram.getToolTipText(
                        MouseEvent(diagram, MouseEvent.MOUSE_MOVED, 0, 0, copyX, copyY, 0, false)))
                }
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `pin state is explicit and belongs to each diagram`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("pin", 1, null, null, "测试", "", null, branch, 0)
            val first = createUnderstandingDiagram(record)
            val second = createUnderstandingDiagram(record)
            try {
                assertFalse(first.navigationPinned)
                first.navigationPinned = true
                assertTrue(first.navigationPinned)
                assertFalse(second.navigationPinned)
                first.navigationPinned = false
                assertFalse(first.navigationPinned)
            } finally {
                first.dispose()
                second.dispose()
            }
        }
    }

    @Test
    fun `disabling auto follow leaves camera still when node and branch change`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("follow", 1, null, null, "测试", "", null, branch, 0)
            val diagram = createUnderstandingDiagram(record)
            try {
                diagram.setSize(900, 600)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                val before = diagram.center
                diagram.autoFollowNode = false
                assertFalse(diagram.autoFollowNode)
                diagram.showUnderstandingPath(listOf("yes-edge"), "yes", centerNode = true)
                diagram.selectUnderstandingNode("end")
                diagram.setChoiceTargets(setOf("no"))
                assertEquals(before.x, diagram.center.x, 0.001)
                assertEquals(before.y, diagram.center.y, 0.001)
                diagram.autoFollowNode = true
                assertTrue(diagram.autoFollowNode)
                assertNotEquals(before.y, diagram.center.y)
            } finally { diagram.dispose() }
        }
    }

    @Test
    fun `editor tabs use short names for root and follow up canvases`() {
        val record = UnderstandingRecord("tab", 1, null, null,
            "后台用户名密码登录从请求到会话与 Cookie 是怎么走的？失败会发生什么？", "", null, branch, 0)
        val root = understandingTabTitle(record, null)
        val child = understandingTabTitle(record, "start")
        assertFalse(root.startsWith("理解图 · "))
        assertTrue(root.endsWith("…"))
        assertEquals("入口", child)
        assertFalse(root.contains(".understanding"))
    }

    @Test
    fun `canvas step controls and branch choice work without bottom buttons`() {
        SwingUtilities.invokeAndWait {
            val record = UnderstandingRecord("branch-controls", 1, null, null, "测试", "", null, branch, 0)
            val diagram = createUnderstandingDiagram(record)
            try {
                diagram.setSize(900, 600)
                diagram.dispatchEvent(ComponentEvent(diagram, ComponentEvent.COMPONENT_RESIZED))
                awaitLayout(diagram)
                diagram.doLayout()
                var previous = 0
                var next = 0
                diagram.setStepActions({ previous++ }, { next++ })
                val previousButton = diagram.components.filterIsInstance<JButton>().single { it.toolTipText == "上一步" }
                val nextButton = diagram.components.filterIsInstance<JButton>().single { it.toolTipText == "下一步" }
                assertTrue(previousButton.y >= diagram.height - previousButton.height - 12)
                assertEquals(previousButton.y, nextButton.y)
                previousButton.doClick()
                nextButton.doClick()
                assertEquals(1, previous)
                assertEquals(1, next)
                var selected: String? = null
                diagram.onNodeSelected { selected = it }
                diagram.setChoiceTargets(setOf("yes", "no"))
                val node = diagram.layout.nodes.getValue("yes").bounds
                val x = (diagram.width / 2 + (node.centerX - diagram.center.x) * diagram.zoom).toInt()
                val y = (diagram.height / 2 + (node.centerY - diagram.center.y) * diagram.zoom).toInt()
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
                diagram.dispatchEvent(MouseEvent(diagram, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
                assertEquals("yes", selected)
            } finally { diagram.dispose() }
        }
    }

    private fun assertPathWithinBounds(layout: UnderstandingGraphLayout, edge: PlacedEdge) {
        val bounds = edge.path.bounds2D
        assertTrue(bounds.minX >= layout.bounds.minX)
        assertTrue(bounds.minY >= layout.bounds.minY)
        assertTrue(bounds.maxX <= layout.bounds.maxX)
        assertTrue(bounds.maxY <= layout.bounds.maxY)
    }

    private fun awaitLayout(diagram: UnderstandingDiagram) {
        diagram.setLayoutPreference(GraphLayoutPreference.VERTICAL)
        if (diagram.layout.nodes.isNotEmpty() && diagram.activeDirection == LayoutDirection.VERTICAL) return
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        val started = System.nanoTime()
        var timedOut = false
        val timer = Timer(5) {
            if (diagram.layout.nodes.isNotEmpty() && diagram.activeDirection == LayoutDirection.VERTICAL) {
                loop.exit()
            } else if (System.nanoTime() - started > 5_000_000_000L) {
                timedOut = true
                loop.exit()
            }
        }
        timer.start()
        loop.enter()
        timer.stop()
        assertFalse(timedOut, "布局任务超时")
    }
}
