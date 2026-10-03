package com.master.understanding.diagram

import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepDirection
import com.master.understanding.core.StepResult
import com.master.understanding.model.BrowseState
import com.master.understanding.model.UnderstandingGraph
import java.awt.Point
import java.awt.geom.Point2D
import javax.swing.JPopupMenu

/** Owns ordinary directional choices and explicit completion while publishing one shared route snapshot. */
internal class DiagramRouteNavigation(
    private val diagram: UnderstandingDiagram,
    private val graph: UnderstandingGraph,
    val explorer: StaticExplorer,
    private val onMoved: (StaticExplorer) -> Unit,
    private val choices: RouteChoicePresenter = swingRouteChoicePresenter,
    private val isCurrent: (String) -> Boolean = { true },
) : AutoCloseable {
    constructor(
        diagram: UnderstandingDiagram,
        explorer: StaticExplorer,
        commit: () -> Unit,
        presentChoices: (JPopupMenu) -> Unit = { menu ->
            if (diagram.isShowing) menu.show(diagram, diagram.width / 2, diagram.height / 2)
        },
        isCurrent: () -> Boolean = { true },
    ) : this(diagram, diagram.record.graph, explorer, { commit() },
        RouteChoicePresenter { graph, direction, edges, _, _, finished ->
            RouteChoicePopup(graph, direction, edges, finished).apply { presentChoices(menu) }
        }, { isCurrent() })

    private var generation = 0L
    private var popup: AutoCloseable? = null
    private var closed = false
    private var pendingEdges = emptyList<com.master.understanding.model.GraphEdge>()
    private var pendingDirection = StepDirection.NEXT
    private var completing = false
    private var cursor = 0
    private var savedCenter: Point2D.Double? = null
    private var savedZoom = 1.0
    val hasChoice: Boolean get() = pendingEdges.isNotEmpty()
    val isClosed: Boolean get() = closed
    var onActivated: () -> Unit = {}
    private val selection = diagram.onNodeSelected(::focus)
    private val clusterSelection = diagram.onClusterSelected { select(it, centerNode = false) }
    private val completeSelection = diagram.onCompleteRouteSelected { route, target ->
        if (ready(target)) {
            cancelChoice()
            if (explorer.selectCompletePath(route.edgeIds, target)) commit()
        }
    }

    init {
        diagram.routeNavigation = this
        diagram.completeRouteNavigation.configureScopes(explorer.routeScopes)
        diagram.completeRouteNavigation.canConfirm = { ready(explorer.currentNodeId) }
        diagram.setStepActions(previous = { step(StepDirection.PREVIOUS) }, next = { step(StepDirection.NEXT) })
        render(centerNode = false)
    }

    fun focus(nodeId: String) = select(nodeId, centerNode = true)

    /** A graph click always replaces pending choices with the clicked node's full upstream view. */
    fun select(nodeId: String) = focus(nodeId)

    private fun select(nodeId: String, centerNode: Boolean) {
        if (!ready(nodeId)) return
        cancelChoice()
        if (!explorer.jumpTo(nodeId)) return
        render(centerNode)
        onActivated()
        onMoved(explorer)
    }

    private fun step(direction: StepDirection) {
        if (!ready(explorer.currentNodeId) || hasChoice) return
        cancelChoice()
        onActivated()
        handle(if (direction == StepDirection.PREVIOUS) explorer.previous() else explorer.next(), direction, false)
    }

    /** The existing completion button remains an explicit operation with its own staged incoming choices. */
    fun complete(reselectNode: String? = null) {
        if (!ready(explorer.currentNodeId)) return
        cancelChoice()
        if (reselectNode != null && reselectNode != explorer.currentNodeId) explorer.jumpTo(reselectNode)
        onActivated()
        handle(explorer.completeRoute(reselectNode != null), StepDirection.PREVIOUS, true)
    }

    private fun handle(result: StepResult, direction: StepDirection, completion: Boolean) {
        when (result) {
            is StepResult.Moved -> {
                clearChoice()
                savedCenter = null
                commit()
            }
            is StepResult.Choose -> showChoices(result, direction, completion)
            else -> cancelChoice()
        }
    }

    private fun showChoices(result: StepResult.Choose, direction: StepDirection, completion: Boolean) {
        clearChoice()
        if (savedCenter == null) {
            savedCenter = diagram.center
            savedZoom = diagram.zoom
        }
        pendingEdges = result.edges
        pendingDirection = direction
        completing = completion
        cursor = 0
        diagram.setChoiceTargets(result.edges.mapTo(linkedSetOf()) {
            if (direction == StepDirection.NEXT) it.to else it.from
        })
        render(centerNode = false)
        val request = generation
        val snapshot = explorer.navigationState
        val handle = choices.show(graph, direction, result.edges, diagram.jComponent,
            Point(if (direction == StepDirection.PREVIOUS) 12 else 48, (diagram.height - 48).coerceAtLeast(0)),
        ) { edgeId ->
            if (closed || request != generation || explorer.navigationState != snapshot) return@show
            if (!ready(explorer.currentNodeId)) return@show
            if (edgeId == null) cancelChoice() else choose(edgeId)
        }
        if (request == generation) popup = handle else handle.close()
    }

    private fun choose(edgeId: String) {
        if (!ready(explorer.currentNodeId) || pendingEdges.none { it.id == edgeId }) return
        val direction = pendingDirection
        val completion = completing
        val result = if (completion) explorer.chooseIncoming(edgeId) else explorer.choose(edgeId, direction)
        handle(result, direction, completion)
    }

    /** External source feedback restores the prefix and suffix without another source-selection callback. */
    fun synchronize(nodeId: String, state: BrowseState?): Boolean {
        if (!ready(nodeId)) return false
        if (nodeId == explorer.currentNodeId && state?.nodeId == nodeId &&
            (state.navigationState?.let { it == explorer.navigationState } ?: (state.staticPath == explorer.path))) {
            return false
        }
        val previous = explorer.navigationState
        cancelChoice()
        if (state?.nodeId == nodeId) explorer.restorePath(state.staticPath, nodeId, state.navigationState)
        else explorer.jumpTo(nodeId)
        if (previous == explorer.navigationState) return false
        render(centerNode = true)
        return true
    }

    fun synchronize(path: List<String>, nodeId: String) {
        if (path == explorer.path && nodeId == explorer.currentNodeId) return
        synchronize(nodeId, BrowseState(diagram.record.id, nodeId, path))
    }

    fun moveCandidate(delta: Int): Boolean {
        if (!hasChoice) return false
        if (!ready(explorer.currentNodeId)) return true
        val nodes = pendingEdges.map { if (pendingDirection == StepDirection.NEXT) it.to else it.from }.distinct()
        cursor = (cursor + delta + nodes.size) % nodes.size
        diagram.hoveredNodeId = nodes[cursor]
        diagram.repaint()
        return true
    }

    fun confirmCandidate(): Boolean {
        if (!hasChoice) return false
        if (!ready(explorer.currentNodeId)) return true
        val nodes = pendingEdges.map { if (pendingDirection == StepDirection.NEXT) it.to else it.from }.distinct()
        val nodeId = nodes[cursor.coerceIn(nodes.indices)]
        pendingEdges.singleOrNull { (if (pendingDirection == StepDirection.NEXT) it.to else it.from) == nodeId }
            ?.let { choose(it.id) }
        return true
    }

    private fun commit() {
        render(centerNode = true)
        onActivated()
        onMoved(explorer)
    }

    fun refresh(center: Boolean = false) = render(center)

    private fun render(centerNode: Boolean) {
        diagram.completeRouteNavigation.showNavigation(explorer.navigationState, explorer.currentNodeId, centerNode)
        diagram.setStepAvailability(!hasChoice && explorer.canPrevious, !hasChoice && explorer.canNext)
        diagram.controls.updateRoute(visible = explorer.canComplete || hasChoice, choosing = hasChoice,
            notice = explorer.routeNotice,
        ) { if (hasChoice) cancelChoice() else complete() }
    }

    private fun clearChoice() {
        generation++
        popup?.close()
        popup = null
        pendingEdges = emptyList()
        diagram.setChoiceTargets(emptySet())
    }

    /** Invalidates pending callbacks while retaining the committed navigation snapshot. */
    fun cancelChoice() {
        clearChoice()
        explorer.cancelIncoming()
        savedCenter?.let { center ->
            diagram.cameraTimer.stop()
            diagram.zoom = savedZoom
            diagram.centerX = center.x
            diagram.centerY = center.y
            diagram.repaint()
            diagram.queueViewportSave()
        }
        savedCenter = null
        if (!closed) render(centerNode = false)
    }

    fun cancel() = cancelChoice()

    private fun ready(nodeId: String): Boolean {
        if (closed) return false
        if (isCurrent(nodeId)) return true
        close()
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelChoice()
        selection.close()
        clusterSelection.close()
        completeSelection.close()
        onActivated = {}
        diagram.completeRouteNavigation.canConfirm = { false }
        diagram.routeNavigation = null
        diagram.setStepActions(null, null)
        diagram.controls.updateRoute(visible = false, choosing = false, notice = null, action = null)
    }
}
