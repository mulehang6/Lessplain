package com.master.lessplain.diagram

import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.abs
import kotlin.math.min

/** Repairs connectors in existing free corridors without moving any node or replacing ELK layout. */
internal class LocalEdgeRouter(
    private val nodes: Map<String, PlacedNode>,
    orderedEdges: List<PlacedEdge>,
    private val extras: List<Rectangle2D>,
    private val edgeObstacles: Map<String, List<Rectangle2D>> = emptyMap(),
    private val fixedPorts: Map<String, DiagramEdgePorts> = emptyMap(),
) {
    private val outgoing = orderedEdges.groupBy { it.edge.from }
    private val incoming = orderedEdges.groupBy { it.edge.to }

    fun route(original: PlacedEdge, obstacles: EdgeRoutingObstacles, moved: Boolean): PlacedEdge {
        var best = placeLabel(original, obstacles, preservePosition = !moved)
        var bestCost = if (moved) Double.POSITIVE_INFINITY else cost(best, obstacles)
        if (!moved && obstacles.collisionCost(best) == 0.0) return best
        fun consider(points: List<Point2D.Double>): PlacedEdge? {
            val target = points.last()
            val previous = points.dropLast(1).last { it.distance(target) > 0.01 }
            val candidate = placeLabel(original.copy(path = roundedEdgePath(points), arrowX = target.x,
                arrowY = target.y, arrowDx = target.x - previous.x, arrowDy = target.y - previous.y,
                labelBounds = null), obstacles, preservePosition = false)
            val collision = obstacles.collisionCost(candidate)
            val score = collision + lengthCost(candidate)
            if (score < bestCost) {
                best = candidate
                bestCost = score
            }
            return candidate.takeIf { collision == 0.0 }
        }
        for ((sourceSide, targetSide) in sidePairs(original)) {
            for (variant in listOf(0.0, 12.0, -12.0, 24.0, -24.0)) {
                for (points in candidates(original, variant, sourceSide, targetSide, obstacles)) {
                    consider(points)?.let { return it }
                }
            }
        }
        if (edgeObstacles[original.edge.id].orEmpty().isNotEmpty() || original.edge.id in fixedPorts) {
            for ((sourceSide, targetSide) in sidePairs(original)) {
                for (variant in listOf(0.0, 12.0, -12.0, 24.0, -24.0)) {
                    val shell = candidates(original, variant, sourceSide, targetSide, obstacles).first()
                    val boxes = nodes.values.map { it.bounds } + extras + edgeObstacles[original.edge.id].orEmpty()
                    val corridor = routeOrthogonalCorridor(shell[1], shell[shell.lastIndex - 1],
                        original.edge.id, boxes, obstacles) ?: continue
                    val points = listOf(shell.first()) + corridor + shell.last()
                    consider(points)?.let { return it }
                }
            }
        }
        // Overlapping user-placed nodes can make a clear route impossible; keep the least obstructed path.
        return best
    }

    private fun cost(edge: PlacedEdge, obstacles: EdgeRoutingObstacles): Double =
        obstacles.collisionCost(edge) + lengthCost(edge)

    private fun lengthCost(edge: PlacedEdge): Double = edge.segments.sumOf { it.length } + edge.segments.size * 8

    private fun sidePairs(edge: PlacedEdge): List<Pair<RoutingSide, RoutingSide>> {
        fixedPorts[edge.edge.id]?.let { return listOf(it.source.side to it.target.side) }
        val source = nodes.getValue(edge.edge.from).bounds
        val target = nodes.getValue(edge.edge.to).bounds
        val self = edge.edge.from == edge.edge.to
        val primary = if (self) RoutingSide.EAST else if (abs(target.centerY - source.centerY) >=
            abs(target.centerX - source.centerX)) {
            if (target.centerY >= source.centerY) RoutingSide.SOUTH else RoutingSide.NORTH
        } else if (target.centerX >= source.centerX) RoutingSide.EAST else RoutingSide.WEST
        val targetSide = if (self) RoutingSide.NORTH else primary.opposite()
        val sources = listOf(primary) + RoutingSide.entries.filter { it != primary }
        val targets = listOf(targetSide) + RoutingSide.entries.filter { it != targetSide }
        return sources.flatMap { from -> targets.filter { !self || it != from }.map { from to it } }
    }

    private fun port(bounds: Rectangle2D, side: RoutingSide, group: List<PlacedEdge>, edge: PlacedEdge,
        variant: Double): Point2D.Double {
        val offset = portOffset(group, edge, if (side.dx == 0) bounds.width else bounds.height, variant)
        return if (side.dx == 0) Point2D.Double(bounds.centerX + offset, if (side.dy < 0) bounds.minY else bounds.maxY)
        else Point2D.Double(if (side.dx < 0) bounds.minX else bounds.maxX, bounds.centerY + offset)
    }

    private fun candidates(edge: PlacedEdge, variant: Double, sourceSide: RoutingSide,
        targetSide: RoutingSide, obstacles: EdgeRoutingObstacles): Sequence<List<Point2D.Double>> = sequence {
        val source = nodes.getValue(edge.edge.from).bounds
        val target = nodes.getValue(edge.edge.to).bounds
        val self = edge.edge.from == edge.edge.to
        val vertical = sourceSide.dx == 0
        val fixed = fixedPorts[edge.edge.id]
        val first = fixed?.source?.point ?: port(source, sourceSide, outgoing.getValue(edge.edge.from), edge, variant)
        val last = fixed?.target?.point ?: port(target, targetSide, incoming.getValue(edge.edge.to), edge, variant)
        // Distinct departure/arrival lanes can require bends at different distances from a shared node.
        val stub = (if (self) 24 else 18) + abs(variant)
        val start = Point2D.Double(first.x + sourceSide.dx * stub, first.y + sourceSide.dy * stub)
        val end = Point2D.Double(last.x + targetSide.dx * stub, last.y + targetSide.dy * stub)
        fun path(vararg middle: Point2D.Double) = listOf(first, start) + middle + listOf(end, last)
        yield(path(Point2D.Double(start.x, end.y)))
        yield(path(Point2D.Double(end.x, start.y)))
        val boxes = nodes.values.map { it.bounds } + extras + edgeObstacles[edge.edge.id].orEmpty()
        fun channels(horizontal: Boolean): List<Double> {
            val a = if (horizontal) start.y else start.x
            val b = if (horizontal) end.y else end.x
            val midpoint = (a + b) / 2
            val all = (listOf(midpoint + variant, a - 24, a + 24, b - 24, b + 24) +
                boxes.flatMap { if (horizontal) listOf(it.minY - 24, it.maxY + 24)
                    else listOf(it.minX - 24, it.maxX + 24) } +
                obstacles.occupiedChannels(edge.edge.id, horizontal) +
                edge.segments.flatMap { if (horizontal) listOf(it.y1 - 12, it.y1 + 12)
                    else listOf(it.x1 - 12, it.x1 + 12) }).distinct().sorted()
            return (all.sortedBy { abs(it - midpoint) }.take(24) + listOf(all.first(), all.last())).distinct()
        }
        val horizontalChannels = channels(true)
        val verticalChannels = channels(false)
        if (vertical && !self) {
            horizontalChannels.forEach { y -> yield(path(Point2D.Double(start.x, y), Point2D.Double(end.x, y))) }
            verticalChannels.forEach { x -> yield(path(Point2D.Double(x, start.y), Point2D.Double(x, end.y))) }
        } else {
            verticalChannels.forEach { x -> yield(path(Point2D.Double(x, start.y), Point2D.Double(x, end.y))) }
            horizontalChannels.forEach { y -> yield(path(Point2D.Double(start.x, y), Point2D.Double(end.x, y))) }
        }
    }

    private fun portOffset(group: List<PlacedEdge>, edge: PlacedEdge, length: Double, variant: Double): Double {
        val index = group.indexOfFirst { it.edge.id == edge.edge.id }
        val slot = if (index == 0) 0 else (index + 1) / 2 * if (index % 2 == 1) 1 else -1
        val limit = (length / 2 - 12).coerceAtLeast(0.0)
        val step = min(12.0, limit / ((group.size + 1) / 2).coerceAtLeast(1))
        return (slot * step + variant).coerceIn(-limit, limit)
    }

    private fun placeLabel(edge: PlacedEdge, obstacles: EdgeRoutingObstacles, preservePosition: Boolean): PlacedEdge {
        val text = edge.edge.condition?.takeIf(String::isNotBlank) ?: return edge
        val (width, height) = DiagramTextMetrics.edgeLabelSize(text)
        val positions = buildList {
            if (preservePosition) edge.resolvedLabelBounds?.let { add(it.centerX to it.centerY) }
            edge.segments.sortedByDescending { it.length }.take(12).forEach { segment ->
                for (fraction in listOf(0.5, 0.25, 0.75)) {
                    val x = segment.x1 + (segment.x2 - segment.x1) * fraction
                    val y = segment.y1 + (segment.y2 - segment.y1) * fraction
                    add(x to y)
                    if (abs(segment.y1 - segment.y2) < 0.01) {
                        add(x to y - height / 2 - 6)
                        add(x to y + height / 2 + 6)
                    } else {
                        add(x - width / 2 - 6 to y)
                        add(x + width / 2 + 6 to y)
                    }
                }
            }
        }
        val bounds = positions.map { (x, y) -> Rectangle2D.Double(x - width / 2, y - height / 2, width, height) }
            .minByOrNull { obstacles.labelCost(it, edge.edge.id) +
                if (it.intersects(edge.arrowPath.bounds2D)) 100_000.0 else 0.0 } ?: return edge
        return edge.copy(labelX = bounds.centerX, labelY = bounds.centerY, labelBounds = bounds)
    }
}
