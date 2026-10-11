package com.lessplain.diagram

import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.util.PriorityQueue
import kotlin.math.abs

/** 单通道折线无法绕开分组时，为固定端口对使用可见性网格回退。 */
internal fun routeOrthogonalCorridor(
    start: Point2D.Double,
    end: Point2D.Double,
    edgeId: String,
    boxes: List<Rectangle2D>,
    obstacles: EdgeRoutingObstacles,
): List<Point2D.Double>? {
    fun channels(horizontal: Boolean): List<Double> {
        val first = if (horizontal) start.y else start.x
        val last = if (horizontal) end.y else end.x
        val middle = (first + last) / 2
        val borders = boxes.flatMap { box ->
            if (horizontal) listOf(box.minY - 12, box.maxY + 12)
            else listOf(box.minX - 12, box.maxX + 12)
        }
        val occupied = obstacles.occupiedChannels(edgeId, horizontal).sortedBy { abs(it - middle) }.take(32)
        return (listOf(first, last) + borders + occupied).distinct().sorted()
    }
    val xs = channels(false)
    val ys = channels(true)
    val columns = xs.size
    val size = columns * ys.size
    val startIndex = ys.indexOf(start.y) * columns + xs.indexOf(start.x)
    val endIndex = ys.indexOf(end.y) * columns + xs.indexOf(end.x)
    val distances = DoubleArray(size * 2) { Double.POSITIVE_INFINITY }
    val previous = IntArray(size * 2) { -1 }
    data class Step(val state: Int, val distance: Double)
    val pending = PriorityQueue(compareBy<Step> { it.distance }.thenBy { it.state })
    for (axis in 0..1) {
        distances[startIndex * 2 + axis] = 0.0
        pending.add(Step(startIndex * 2 + axis, 0.0))
    }
    fun point(index: Int): Point2D.Double = Point2D.Double(xs[index % columns], ys[index / columns])
    var finalState = -1
    while (pending.isNotEmpty()) {
        val step = pending.remove()
        if (step.distance > distances[step.state]) continue
        val index = step.state / 2
        if (index == endIndex) {
            finalState = step.state
            break
        }
        val x = index % columns
        val y = index / columns
        val here = point(index)
        val neighbours = listOfNotNull(
            (index - 1).takeIf { x > 0 },
            (index + 1).takeIf { x + 1 < columns },
            (index - columns).takeIf { y > 0 },
            (index + columns).takeIf { y + 1 < ys.size },
        )
        neighbours.forEach { next ->
            val there = point(next)
            val segment = RoutingSegment(here.x, here.y, there.x, there.y)
            val axis = if (here.y == there.y) 0 else 1
            val state = next * 2 + axis
            val distance = step.distance + segment.length + obstacles.corridorCost(segment, edgeId) +
                if (axis != step.state % 2) 8.0 else 0.0
            if (distance < distances[state]) {
                distances[state] = distance
                previous[state] = step.state
                pending.add(Step(state, distance))
            }
        }
    }
    if (finalState < 0) return null
    return buildList {
        var state = finalState
        while (state >= 0) {
            add(point(state / 2))
            state = previous[state]
        }
    }.asReversed()
}
