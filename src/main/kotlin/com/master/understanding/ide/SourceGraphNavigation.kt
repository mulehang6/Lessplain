package com.master.understanding.ide

import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepDirection
import com.master.understanding.core.StepResult
import com.master.understanding.diagram.RouteChoicePresenter
import com.master.understanding.diagram.swingRouteChoicePresenter
import com.master.understanding.model.BrowseState
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.storage.UnderstandingRepository
import java.awt.Component
import java.awt.Point

/** Source inlay controls use the same directional edge choices and saved route as graph views. */
internal class SourceGraphNavigation(
    private val repository: UnderstandingRepository,
    private val graphStates: GraphViewStateStore,
    private val isSelected: (recordId: String, nodeId: String) -> Boolean,
    private val selectNode: (record: UnderstandingRecord, nodeId: String) -> Unit,
    private val choices: RouteChoicePresenter = swingRouteChoicePresenter,
) : AutoCloseable {
    private var generation = 0L
    private var popup: AutoCloseable? = null
    private var closed = false

    fun explorer(record: UnderstandingRecord, nodeId: String): StaticExplorer {
        val saved = graphStates.get(GraphLayerKey(record.id)).browse ?: repository.getBrowseState(record.id)
        return StaticExplorer(record.graph, saved?.staticPath.orEmpty(), nodeId, saved?.navigationState)
    }

    fun previous(recordId: String, nodeId: String, component: Component? = null, point: Point = Point()) {
        step(recordId, nodeId, StepDirection.PREVIOUS, component, point)
    }

    fun next(recordId: String, nodeId: String, component: Component, point: Point) {
        step(recordId, nodeId, StepDirection.NEXT, component, point)
    }

    private fun step(recordId: String, nodeId: String, direction: StepDirection, component: Component?, point: Point) {
        if (closed || !isSelected(recordId, nodeId)) return
        cancelChoices()
        if (graphStates.navigationSessions.step(recordId, nodeId, backward = direction == StepDirection.PREVIOUS)) return
        val record = repository.getRecord(recordId)
        val route = explorer(record, nodeId)
        val result = if (direction == StepDirection.PREVIOUS) route.previous() else route.next()
        when (result) {
            is StepResult.Moved -> commit(record, route)
            is StepResult.Choose -> {
                if (component == null) return
                val request = generation
                val snapshot = route.navigationState
                val handle = choices.show(record.graph, direction, result.edges, component, Point(point.x, point.y + 2)) { edgeId ->
                    if (request != generation || !isSelected(recordId, nodeId)) return@show
                    cancelChoices()
                    if (edgeId == null) return@show
                    val currentRecord = repository.getRecord(recordId)
                    if (currentRecord.revision != record.revision) return@show
                    val current = explorer(currentRecord, nodeId)
                    if (current.navigationState != snapshot) return@show
                    if (current.choose(edgeId, direction) is StepResult.Moved) commit(currentRecord, current)
                }
                if (request == generation) popup = handle else handle.close()
            }
            else -> Unit
        }
    }

    fun cancelChoices() {
        generation++
        popup?.close()
        popup = null
    }

    private fun commit(record: UnderstandingRecord, explorer: StaticExplorer) {
        val nodeId = explorer.currentNodeId
        val previous = repository.getBrowseState(record.id)
        graphStates.update(GraphLayerKey(record.id)) { state ->
            val browse = state.browse ?: previous ?: BrowseState(record.id)
            state.copy(browse = browse.copy(nodeId = nodeId, staticPath = explorer.path,
                navigationState = explorer.navigationState))
        }
        if (!repository.readOnly) {
            repository.saveBrowseState((previous ?: BrowseState(record.id)).copy(
                nodeId = nodeId,
                staticPath = explorer.path,
                navigationState = explorer.navigationState,
            ))
        }
        selectNode(record, nodeId)
    }

    override fun close() {
        closed = true
        cancelChoices()
    }
}
