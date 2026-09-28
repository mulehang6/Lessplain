package com.master.understanding.diagram

import com.master.understanding.ide.GraphLayerKey
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.model.BrowseState

internal fun graphBrowseState(service: UnderstandingService, key: GraphLayerKey): BrowseState? =
    service.graphStates.get(key).browse ?:
        if (key.questionNodeId == null) service.repository.getBrowseState(key.recordId) else null

internal fun UnderstandingDiagram.bindGraphState(service: UnderstandingService, key: GraphLayerKey) {
    val state = service.graphStates.get(key)
    navigationPinned = state.navigationPinned
    autoFollowNode = state.autoFollowNode
    flowEnabled = state.flowEnabled
    setNodePositions(state.nodePositions)
    onPinChanged { value -> service.graphStates.update(key) { it.copy(navigationPinned = value) } }
    onAutoFollowChanged { value -> service.graphStates.update(key) { it.copy(autoFollowNode = value) } }
    onFlowChanged { value -> service.graphStates.update(key) { it.copy(flowEnabled = value) } }
    onNodePositionsChanged { value -> service.graphStates.update(key) { it.copy(nodePositions = value) } }
    bindStateSubscription(service.graphStates.listen(key) { current ->
        navigationPinned = current.navigationPinned
        autoFollowNode = current.autoFollowNode
        flowEnabled = current.flowEnabled
        setNodePositions(current.nodePositions)
    })
}

internal fun saveGraphViewState(
    service: UnderstandingService,
    key: GraphLayerKey,
    diagram: UnderstandingDiagram,
    path: List<String>,
    nodeId: String?,
) {
    val center = diagram.center
    service.graphStates.update(key) { previous ->
        previous.copy(
            browse = BrowseState(key.canvasId, nodeId, path, diagram.zoom, center.x, center.y, true),
        )
    }
}
