package com.master.understanding.diagram

import com.master.understanding.ide.GraphLayerKey
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.model.BrowseState
import com.master.understanding.model.RouteNavigationState

internal fun graphBrowseState(service: UnderstandingService, key: GraphLayerKey): BrowseState? =
    service.graphStates.get(key).browse ?:
        if (key.questionNodeId == null) service.repository.getBrowseState(key.recordId) else null

internal fun UnderstandingDiagram.bindGraphState(service: UnderstandingService, key: GraphLayerKey) {
    val state = service.graphStates.get(key)
    navigationPinned = state.navigationPinned
    autoFollowNode = state.autoFollowNode
    flowEnabled = state.flowEnabled
    setDirectionState(LayoutDirection.VERTICAL, state.verticalViewport, state.verticalPositions)
    setDirectionState(LayoutDirection.HORIZONTAL, state.horizontalViewport, state.horizontalPositions)
    setLayoutPreference(state.layoutPreference, persist = false)
    onPinChanged { value -> service.graphStates.update(key) { it.copy(navigationPinned = value) } }
    onAutoFollowChanged { value -> service.graphStates.update(key) { it.copy(autoFollowNode = value) } }
    onFlowChanged { value -> service.graphStates.update(key) { it.copy(flowEnabled = value) } }
    onNodePositionsChanged { value ->
        service.graphStates.update(key) { it.withPositions(activeDirection, value) }
    }
    onLayoutPreferenceChanged { value ->
        service.graphStates.update(key) { it.copy(layoutPreference = value) }
    }
    bindStateSubscription(service.graphStates.listen(key) { current ->
        navigationPinned = current.navigationPinned
        autoFollowNode = current.autoFollowNode
        flowEnabled = current.flowEnabled
        setDirectionState(LayoutDirection.VERTICAL, current.verticalViewport, current.verticalPositions)
        setDirectionState(LayoutDirection.HORIZONTAL, current.horizontalViewport, current.horizontalPositions)
        setLayoutPreference(current.layoutPreference, persist = false)
    })
}

internal fun saveGraphViewState(
    service: UnderstandingService,
    key: GraphLayerKey,
    diagram: UnderstandingDiagram,
    path: List<String>,
    nodeId: String?,
    navigationState: RouteNavigationState? = null,
) {
    val center = diagram.center
    service.graphStates.update(key) { previous ->
        val browse = BrowseState(key.canvasId, nodeId, path, diagram.zoom, center.x, center.y, true, navigationState)
        previous.withViewport(diagram.activeDirection, browse).copy(browse = browse)
    }
}
