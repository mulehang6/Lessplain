package com.master.lessplain.ide

import com.master.lessplain.core.StaticExplorer
import com.master.lessplain.core.StepDirection
import com.master.lessplain.core.StepResult
import com.master.lessplain.model.BrowseState
import com.master.lessplain.model.RouteNavigationState
import com.master.lessplain.model.UnderstandingRecord
import com.master.lessplain.storage.UnderstandingRepository

/** 源码 inline 控件与图视图共用方向选择和已保存路线。 */
internal class SourceGraphNavigation(
    private val repository: UnderstandingRepository,
    private val graphStates: GraphViewStateStore,
    private val isSelected: (recordId: String, nodeId: String) -> Boolean,
    private val selectNode: (record: UnderstandingRecord, nodeId: String) -> Unit,
    private val showGraph: (UnderstandingRecord, ready: () -> Unit) -> Unit,
) : AutoCloseable {
    /** 相同源码点击共用工具窗口显示及画布绑定请求。 */
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
                // 打开图可能稍后才完成，不能继续操作已经变化的源码上下文。
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
