package com.master.understanding.diagram

import java.awt.geom.FlatteningPathIterator
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Straight pieces retain short rounded corners for obstacle checks without flattening the whole canvas. */
internal data class RoutingSegment(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val length: Double get() = kotlin.math.hypot(x2 - x1, y2 - y1)
    val bounds: Rectangle2D.Double get() = Rectangle2D.Double(
        min(x1, x2) - 4, min(y1, y2) - 4, abs(x2 - x1) + 8, abs(y2 - y1) + 8,
    )

    /** Long parallel runs closer than eight graph pixels cannot be followed independently. */
    fun sharedRun(other: RoutingSegment): Double = when {
        abs(y1 - y2) < 0.01 && abs(other.y1 - other.y2) < 0.01 && abs(y1 - other.y1) < 8 ->
            max(0.0, min(max(x1, x2), max(other.x1, other.x2)) - max(min(x1, x2), min(other.x1, other.x2)))
        abs(x1 - x2) < 0.01 && abs(other.x1 - other.x2) < 0.01 && abs(x1 - other.x1) < 8 ->
            max(0.0, min(max(y1, y2), max(other.y1, other.y2)) - max(min(y1, y2), min(other.y1, other.y2)))
        else -> 0.0
    }

    fun crosses(rectangle: Rectangle2D): Boolean = rectangle.intersectsLine(x1, y1, x2, y2)
}

/** Geometry shared by conflict detection, label placement and routing quality tests. */
internal fun routingSegments(edge: PlacedEdge): List<RoutingSegment> {
    val iterator = FlatteningPathIterator(edge.path.getPathIterator(null), 1.0)
    val coordinates = DoubleArray(6)
    var x = 0.0
    var y = 0.0
    return buildList {
        while (!iterator.isDone) {
            val kind = iterator.currentSegment(coordinates)
            if (kind == PathIterator.SEG_LINETO) {
                val segment = RoutingSegment(x, y, coordinates[0], coordinates[1])
                if (segment.length > 0.01) add(segment)
            }
            x = coordinates[0]
            y = coordinates[1]
            iterator.next()
        }
    }
}

/** Index individual pieces rather than large edge bounding boxes, keeping dense graph checks local. */
private class RoutingSpatialIndex<T> {
    private val cells = hashMapOf<Pair<Int, Int>, MutableSet<T>>()

    fun add(bounds: Rectangle2D, value: T) {
        eachCell(bounds) { key -> cells.getOrPut(key) { linkedSetOf() }.add(value) }
    }

    fun remove(bounds: Rectangle2D, value: T) {
        eachCell(bounds) { key ->
            cells[key]?.let { values ->
                values.remove(value)
                if (values.isEmpty()) cells.remove(key)
            }
        }
    }

    fun query(bounds: Rectangle2D): Set<T> = buildSet {
        eachCell(bounds) { key -> cells[key]?.let(::addAll) }
    }

    private inline fun eachCell(bounds: Rectangle2D, visit: (Pair<Int, Int>) -> Unit) {
        for (x in floor(bounds.minX / 160).toInt()..floor(bounds.maxX / 160).toInt()) {
            for (y in floor(bounds.minY / 160).toInt()..floor(bounds.maxY / 160).toInt()) visit(x to y)
        }
    }
}

private data class EdgeReservation(
    val edgeId: String,
    val bounds: Rectangle2D,
    val segment: RoutingSegment? = null,
    val label: Boolean = false,
)

/** Reservations allow necessary crossings but reject hidden labels, arrows and long shared runs. */
internal class EdgeRoutingObstacles(nodes: Collection<PlacedNode>, private val extras: List<Rectangle2D>) {
    private val nodeIndex = RoutingSpatialIndex<PlacedNode>().apply {
        nodes.forEach { add(it.bounds, it) }
    }
    private val edgeIndex = RoutingSpatialIndex<EdgeReservation>()
    private val reservations = hashMapOf<String, List<EdgeReservation>>()

    fun add(edge: PlacedEdge) {
        val values = buildList {
            edge.segments.forEach { add(EdgeReservation(edge.edge.id, it.bounds, it)) }
            edge.resolvedLabelBounds?.let { add(EdgeReservation(edge.edge.id, it, label = true)) }
            add(EdgeReservation(edge.edge.id, edge.arrowPath.bounds2D))
        }
        reservations[edge.edge.id] = values
        values.forEach { edgeIndex.add(it.bounds, it) }
    }

    fun remove(id: String) {
        reservations.remove(id)?.forEach { edgeIndex.remove(it.bounds, it) }
    }

    fun crossesNode(edge: PlacedEdge): Boolean = edge.segments.any { segment ->
        nodeIndex.query(segment.bounds).any { node -> segment.crosses(interior(node.bounds)) } ||
            extras.any(segment::crosses)
    }

    fun labelCrossesNode(edge: PlacedEdge): Boolean = edge.resolvedLabelBounds?.let { bounds ->
        nodeIndex.query(bounds).any { it.bounds.intersects(bounds) } || extras.any(bounds::intersects)
    } == true

    /** Only actual collisions return IDs; an isolated crossing is not a routing defect. */
    fun conflicts(edge: PlacedEdge): Set<String> = buildSet {
        edge.segments.forEach { segment ->
            edgeIndex.query(segment.bounds).filter { it.edgeId != edge.edge.id }.forEach { obstacle ->
                if (obstacle.segment?.let { segment.sharedRun(it) > 12 } == true ||
                    obstacle.label && segment.crosses(obstacle.bounds)) add(obstacle.edgeId)
            }
        }
        edge.resolvedLabelBounds?.let { label ->
            edgeIndex.query(label).filter { it.edgeId != edge.edge.id }.forEach { obstacle ->
                if (obstacle.segment?.crosses(label) ?: label.intersects(obstacle.bounds)) add(obstacle.edgeId)
            }
        }
        val arrow = edge.arrowPath.bounds2D
        edgeIndex.query(arrow).filter { it.edgeId != edge.edge.id && it.segment == null }.forEach {
            if (arrow.intersects(it.bounds)) add(it.edgeId)
        }
    }

    fun labelCost(bounds: Rectangle2D, edgeId: String): Double {
        val blocked = nodeIndex.query(bounds).count { it.bounds.intersects(bounds) } + extras.count(bounds::intersects)
        val occupied = edgeIndex.query(bounds).count {
            it.edgeId != edgeId && (it.segment?.crosses(bounds) ?: it.bounds.intersects(bounds))
        }
        return blocked * 1_000_000.0 + occupied * 100_000.0
    }

    fun collisionCost(edge: PlacedEdge): Double {
        val blocked = edge.segments.count { segment ->
            nodeIndex.query(segment.bounds).any { segment.crosses(interior(it.bounds)) } || extras.any(segment::crosses)
        }
        return blocked * 1_000_000_000.0 + conflicts(edge).size * 100_000.0 +
            (edge.resolvedLabelBounds?.let { labelCost(it, edge.edge.id) +
                if (it.intersects(edge.arrowPath.bounds2D)) 100_000.0 else 0.0 } ?: 0.0)
    }

    private fun interior(bounds: Rectangle2D): Rectangle2D = Rectangle2D.Double(
        bounds.x + 0.5, bounds.y + 0.5, max(0.0, bounds.width - 1), max(0.0, bounds.height - 1),
    )
}
