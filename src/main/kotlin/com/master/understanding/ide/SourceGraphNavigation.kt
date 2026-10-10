package com.master.understanding.ide

import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepDirection
import com.master.understanding.core.StepResult
import com.master.understanding.model.BrowseState
import com.master.understanding.model.RouteNavigationState
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.storage.UnderstandingRepository

/** Source inlay controls use the same directional edge choices and saved route as graph views. */
internal class SourceGraphNavigation(
    private val repository: UnderstandingRepository,
    private val graphStates: GraphViewStateStore,
    private val isSelected: (recordId: String, nodeId: String) -> Boolean,
    private val selectNode: (record: UnderstandingRecord, nodeId: String) -> Unit,
    private val showGraph: (UnderstandingRecord, ready: () -> Unit) -> Unit,
) : AutoCloseable {
    /** Identical source clicks share the tool-window show and canvas-attachment request. */
    private data class OpeningChoice(
        val recordId: String,
        val revision: Int,
        val nodeId: String,
        val direction: StepDirection,
        val snapshot: RouteNavigationState,
    )

    private var openingChoice: OpeningChoice? = null
    private var pendingGraph: AutoCloseable? = null
    private var generation = 0L
    private var closed = false

    fun explorer(record: UnderstandingRecord, nodeId: String): StaticExplorer {
        val saved = graphStates.get(GraphLayerKey(record.id)).browse ?: repository.getBrowseState(record.id)
        return StaticExplorer(record.graph, saved?.staticPath.orEmpty(), nodeId, saved?.navigationState)
    }

    fun previous(recordId: String, nodeId: String) = step(recordId, nodeId, StepDirection.PREVIOUS)

    fun next(recordId: String, nodeId: String) = step(recordId, nodeId, StepDirection.NEXT)

    private fun step(recordId: String, nodeId: String, direction: StepDirection) {
        if (closed || !isSelected(recordId, nodeId)) return
        val record = repository.getRecord(recordId)
        val route = explorer(record, nodeId)
        val opening = OpeningChoice(recordId, record.revision, nodeId, direction, route.navigationState)
        if (openingChoice == opening) return
        cancelChoices()
        val backward = direction == StepDirection.PREVIOUS
        if (graphStates.navigationSessions.step(recordId, nodeId, backward)) return
        when (if (backward) route.previous() else route.next()) {
            is StepResult.Moved -> commit(record, route)
            is StepResult.Choose -> {
                openingChoice = opening
                val request = generation
                val snapshot = route.navigationState
                // Opening a graph may complete later; never step a changed source context.
                fun valid(): Boolean {
                    if (closed || request != generation || !isSelected(recordId, nodeId)) return false
                    val current = repository.getRecord(recordId)
                    return current.revision == record.revision && explorer(current, nodeId).navigationState == snapshot
                }
                try {
                    showGraph(record) {
                        if (valid()) {
                            pendingGraph = graphStates.navigationSessions.whenAvailable(recordId, nodeId) {
                                if (valid()) {
                                    openingChoice = null
                                    graphStates.navigationSessions.step(recordId, nodeId, backward)
                                } else if (request == generation) cancelChoices()
                            }
                        } else if (request == generation) cancelChoices()
                    }
                } catch (failure: RuntimeException) {
                    cancelChoices()
                    throw failure
                }
            }

            else -> Unit
        }
    }

    fun cancelChoices() {
        generation++
        openingChoice = null
        pendingGraph?.close()
        pendingGraph = null
    }

    fun cancelChoices(recordId: String, nodeId: String) {
        cancelChoices()
        graphStates.navigationSessions.cancelChoices(recordId, nodeId)
    }

    private fun commit(record: UnderstandingRecord, explorer: StaticExplorer) {
        val nodeId = explorer.currentNodeId
        val previous = repository.getBrowseState(record.id)
        graphStates.update(GraphLayerKey(record.id)) { state ->
            val browse = state.browse ?: previous ?: BrowseState(record.id)
            state.copy(
                browse = browse.copy(
                    nodeId = nodeId, staticPath = explorer.path,
                    navigationState = explorer.navigationState,
                ),
            )
        }
        if (!repository.readOnly) {
            repository.saveBrowseState(
                (previous ?: BrowseState(record.id)).copy(
                    nodeId = nodeId,
                    staticPath = explorer.path,
                    navigationState = explorer.navigationState,
                ),
            )
        }
        selectNode(record, nodeId)
    }

    override fun close() {
        closed = true
        cancelChoices()
    }
}
