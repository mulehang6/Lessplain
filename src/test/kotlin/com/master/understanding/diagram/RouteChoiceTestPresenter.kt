package com.master.understanding.diagram

import com.master.understanding.core.StepDirection
import com.master.understanding.model.GraphEdge
import java.awt.Component
import java.awt.Point

internal data class RouteChoiceTestRequest(
    val direction: StepDirection,
    val edges: List<GraphEdge>,
    val component: Component,
    val point: Point,
    val preview: (String?) -> Unit,
    val finished: (String?) -> Unit,
    var closed: Boolean = false,
)

internal fun recordedRouteChoices(requests: MutableList<RouteChoiceTestRequest>) =
    RouteChoicePresenter { _, direction, edges, component, point, preview, finished ->
        val request = RouteChoiceTestRequest(direction, edges, component, Point(point), preview, finished)
        requests += request
        AutoCloseable { request.closed = true }
    }
