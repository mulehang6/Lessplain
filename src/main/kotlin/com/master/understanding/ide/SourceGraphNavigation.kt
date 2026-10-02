package com.master.understanding.ide

import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepResult
import com.master.understanding.model.BrowseState
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.storage.UnderstandingRepository
import java.awt.Component
import java.awt.Point
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/** Keeps source inlay stepping on the same persisted route as the graph views. */
internal class SourceGraphNavigation(
    private val repository: UnderstandingRepository,
    private val graphStates: GraphViewStateStore,
    private val isSelected: (recordId: String, nodeId: String) -> Boolean,
    private val selectNode: (record: UnderstandingRecord, nodeId: String) -> Unit,
) {
    fun explorer(record: UnderstandingRecord, nodeId: String): StaticExplorer {
        val saved = graphStates.get(GraphLayerKey(record.id)).browse ?: repository.getBrowseState(record.id)
        return StaticExplorer(record.graph, saved?.staticPath.orEmpty(), nodeId)
    }

    fun previous(recordId: String, nodeId: String) {
        if (!isSelected(recordId, nodeId)) return
        val record = repository.getRecord(recordId)
        val route = explorer(record, nodeId)
        if (route.back()) commit(record, route)
    }

    fun next(recordId: String, nodeId: String, component: Component, point: Point) {
        if (!isSelected(recordId, nodeId)) return
        val record = repository.getRecord(recordId)
        val route = explorer(record, nodeId)
        when (val result = route.next()) {
            is StepResult.Moved -> commit(record, route)
            is StepResult.Choose -> JPopupMenu().apply {
                result.edges.forEach { edge ->
                    val target = record.graph.nodes.firstOrNull { it.id == edge.to }?.label ?: edge.to
                    add(JMenuItem(edge.condition?.let { "$it → $target" } ?: target).apply {
                        addActionListener {
                            if (!isSelected(recordId, nodeId)) return@addActionListener
                            val current = this@SourceGraphNavigation.explorer(record, nodeId)
                            if (current.choose(edge.id) is StepResult.Moved) commit(record, current)
                        }
                    })
                }
                show(component, point.x, point.y + 2)
            }
            else -> Unit
        }
    }

    private fun commit(record: UnderstandingRecord, explorer: StaticExplorer) {
        val nodeId = explorer.currentNodeId
        val previous = repository.getBrowseState(record.id)
        graphStates.update(GraphLayerKey(record.id)) { state ->
            val browse = state.browse ?: previous ?: BrowseState(record.id)
            state.copy(browse = browse.copy(nodeId = nodeId, staticPath = explorer.path))
        }
        if (!repository.readOnly) {
            repository.saveBrowseState((previous ?: BrowseState(record.id)).copy(
                nodeId = nodeId,
                staticPath = explorer.path,
            ))
        }
        selectNode(record, nodeId)
    }
}
