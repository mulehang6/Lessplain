package com.master.understanding.diagram

import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies that the packaged ELK algorithm can route the shapes used by the plugin. */
class ElkIntegrationTest {
    @Test
    fun `layered layout routes a cross cluster edge in a compound graph`() {
        val root = ElkGraphUtil.createGraph().apply {
            setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
            setProperty(CoreOptions.DIRECTION, Direction.DOWN)
            setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
            setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
        }
        val groups = (0..1).map { ElkGraphUtil.createNode(root).apply {
            identifier = "group-$it"
            setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
            setProperty(CoreOptions.DIRECTION, Direction.DOWN)
            setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        } }
        val children = groups.map { group -> (0..1).map {
            ElkGraphUtil.createNode(group).apply { setDimensions(180.0, 60.0) }
        } }
        ElkGraphUtil.createSimpleEdge(children[0][0], children[0][1])
        val cross = ElkGraphUtil.createSimpleEdge(children[0][1], children[1][0])
        ElkGraphUtil.createSimpleEdge(children[1][0], children[1][1])
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
        assertTrue(groups.all { it.width > 0 && it.height > 0 })
        assertTrue(cross.sections.isNotEmpty())
        assertTrue(cross.sections.all { it.startX.isFinite() && it.endY.isFinite() })
    }

    @Test
    fun `layered layout provides finite orthogonal sections for branches and a cycle`() {
        val root = ElkGraphUtil.createGraph().apply {
            setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
            setProperty(CoreOptions.DIRECTION, Direction.DOWN)
            setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        }
        val nodes = (0..6).map { index ->
            ElkGraphUtil.createNode(root).apply {
                identifier = "node-$index"
                setDimensions(200.0, 64.0)
            }
        }
        val pairs = listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3, 3 to 4, 4 to 5,
            5 to 3, 5 to 5, 1 to 3, 2 to 6)
        val edges = pairs.mapIndexed { index, (from, to) ->
            ElkGraphUtil.createSimpleEdge(nodes[from], nodes[to]).apply {
                identifier = "edge-$index"
                if (index < 2) ElkGraphUtil.createLabel(if (index == 0) "yes" else "no", this)
                    .setDimensions(32.0, 18.0)
            }
        }
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())
        assertEquals(7, root.children.size)
        assertTrue(nodes.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(edges.all { edge -> edge.sections.isNotEmpty() && edge.sections.all {
            it.startX.isFinite() && it.startY.isFinite() && it.endX.isFinite() && it.endY.isFinite()
        } })
        assertTrue(edges.take(2).all { it.labels.single().x.isFinite() })
    }
}
