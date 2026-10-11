package com.lessplain.diagram

import java.awt.Point
import java.awt.geom.Point2D

/** 指针输入和提示共用图坐标下的命中区域。 */
internal fun UnderstandingDiagram.screenToWorld(point: Point): Point2D.Double = Point2D.Double(
    centerX + (point.x - width / 2.0) / zoom,
    centerY + (point.y - height / 2.0) / zoom,
)

internal fun UnderstandingDiagram.clusterTitleAt(point: Point): String? {
    val world = screenToWorld(point)
    return layout.clusters.firstOrNull { DiagramClusterText.titleBounds(it).contains(world) }?.id
}

internal fun UnderstandingDiagram.nodeAt(point: Point): String? {
    val world = screenToWorld(point)
    return layout.nodes.values.firstOrNull { it.bounds.contains(world) }?.node?.id
}

internal fun UnderstandingDiagram.examplePanelAt(point: Point): String? {
    val world = screenToWorld(point)
    return layout.clusters.firstOrNull { it.exampleBounds?.contains(world) == true }?.id
}

internal fun UnderstandingDiagram.draggableNodeAt(point: Point): String? {
    nodeAt(point)?.let { return it }
    val world = screenToWorld(point)
    val padding = 9.0 / zoom
    return layout.nodes.values.asSequence().filter { placed ->
        val b = placed.bounds
        world.x >= b.minX - padding && world.x <= b.maxX + padding &&
            world.y >= b.minY - padding && world.y <= b.maxY + padding
    }.minByOrNull { placed ->
        val b = placed.bounds
        val dx = (world.x - b.centerX) / b.width
        val dy = (world.y - b.centerY) / b.height
        dx * dx + dy * dy
    }?.node?.id
}

internal fun UnderstandingDiagram.hoverNodeAt(point: Point): String? {
    val world = screenToWorld(point)
    val padding = 9.0 / zoom
    val actionEnd = 40.0
    val actionTop = 20.0
    hoveredNodeId?.let { id ->
        layout.nodes[id]?.bounds?.let { bounds ->
            if (world.x >= bounds.x - padding && world.x <= bounds.maxX + padding &&
                world.y >= bounds.y - actionTop - padding && world.y <= bounds.maxY + actionEnd + padding) return id
        }
    }
    return layout.nodes.values.firstOrNull { placed ->
        val bounds = placed.bounds
        world.x >= bounds.x - padding && world.x <= bounds.maxX + padding &&
            world.y >= bounds.y - padding && world.y <= bounds.maxY + padding
    }?.node?.id
}

internal fun UnderstandingDiagram.copyActionAt(point: Point): String? {
    val world = screenToWorld(point)
    val id = hoveredNodeId ?: return null
    return id.takeIf { layout.nodes[it]?.bounds?.let { bounds ->
        DiagramNodePainter.copyBounds(bounds).contains(world)
    } == true }
}

internal fun UnderstandingDiagram.questionActionAt(point: Point): String? {
    val world = screenToWorld(point)
    val id = hoveredNodeId?.takeIf { (questionCounts[it] ?: 0) > 0 } ?: return null
    return id.takeIf { layout.nodes[it]?.bounds?.let { bounds ->
        DiagramNodePainter.questionBounds(bounds).contains(world)
    } == true }
}

internal fun UnderstandingDiagram.noteActionAt(point: Point): String? {
    val world = screenToWorld(point)
    return layout.nodes.values.firstOrNull { placed ->
        (hasNodeNote(placed.node.id) || placed.node.id == hoveredNodeId) &&
            DiagramNodePainter.noteBounds(placed.bounds).contains(world)
    }?.node?.id
}

/** 注释预览放在气泡下方，使气泡仍可点击固定预览。 */
internal fun UnderstandingDiagram.notePopupAnchor(point: Point): Point {
    val node = noteActionAt(point)?.let(layout.nodes::get) ?: return Point(point)
    val bounds = DiagramNodePainter.noteBounds(node.bounds)
    return Point(
        (width / 2.0 + (bounds.x - centerX) * zoom).toInt(),
        (height / 2.0 + (bounds.maxY - centerY) * zoom).toInt() + 6,
    )
}

internal fun UnderstandingDiagram.clusterEditAt(point: Point): String? {
    val world = screenToWorld(point)
    return layout.clusters.firstOrNull {
        it.id == overlays.titles.hovered && DiagramClusterText.editBounds(it).contains(world)
    }?.id
}
