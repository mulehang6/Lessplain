package com.lessplain.diagram

import com.lessplain.ide.GraphLayerKey
import com.lessplain.ide.GraphViewStateStore
import com.lessplain.ide.GraphViewState
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.BrowseState
import com.lessplain.model.RouteNavigationState

internal fun graphBrowseState(service: UnderstandingService, key: GraphLayerKey): BrowseState? =
    service.graphStates.get(key).browse ?:
        if (key.questionNodeId == null) service.repository.getBrowseState(key.recordId) else null

internal fun UnderstandingDiagram.bindGraphState(store: GraphViewStateStore, key: GraphLayerKey) {
    applyGraphState(store.get(key))
    onPinChanged { value -> store.update(key) { it.copy(navigationPinned = value) } }
    onAutoFollowChanged { value -> store.update(key) { it.copy(autoFollowNode = value) } }
    onFlowChanged { value -> store.update(key) { it.copy(flowEnabled = value) } }
    onNodePositionsChanged { value ->
        store.update(key) { it.withPositions(activeDirection, value) }
    }
    onLayoutPreferenceChanged { value ->
        store.update(key) { it.copy(layoutPreference = value) }
    }
    bindStateSubscription(store.listen(key, ::applyGraphState))
}

/** 初次绑定和实时更新应用相同的方向独立状态。 */
private fun UnderstandingDiagram.applyGraphState(state: GraphViewState) {
    navigationPinned = state.navigationPinned
    autoFollowNode = state.autoFollowNode
    flowEnabled = state.flowEnabled
    setDirectionState(LayoutDirection.VERTICAL, state.verticalViewport, state.verticalPositions)
    setDirectionState(LayoutDirection.HORIZONTAL, state.horizontalViewport, state.horizontalPositions)
    setLayoutPreference(state.layoutPreference, persist = false)
}

internal fun saveGraphViewState(
    store: GraphViewStateStore,
    key: GraphLayerKey,
    diagram: UnderstandingDiagram,
    path: List<String>,
    nodeId: String?,
    navigationState: RouteNavigationState? = null,
) {
    val center = diagram.center
    store.update(key) { previous ->
        val browse = BrowseState(key.canvasId, nodeId, path, diagram.zoom, center.x, center.y, true, navigationState)
        previous.withViewport(diagram.activeDirection, browse).copy(browse = browse)
    }
}
