package com.lessplain.diagram

import com.lessplain.core.StaticExplorer
import com.lessplain.core.StepDirection
import com.lessplain.core.StepResult
import com.lessplain.model.BrowseState
import com.lessplain.model.GraphEdge

/** 处理普通方向选择和显式补全，同时发布统一的路线快照。 */
internal class DiagramRouteNavigation(
    private val diagram: UnderstandingDiagram,
    val explorer: StaticExplorer,
    private val onMoved: (StaticExplorer) -> Unit,
    private val isCurrent: (String) -> Boolean = { true },
) : AutoCloseable {
    private var closed = false
    private var pendingEdges = emptyList<GraphEdge>()
    private var pendingDirection = StepDirection.NEXT
    private var completing = false
    private var cursor = 0
    val hasChoice: Boolean get() = pendingEdges.isNotEmpty()
    val isClosed: Boolean get() = closed
    var onActivated: () -> Unit = {}
    private val selection = diagram.onNodeSelected(::focus)
    private val clusterSelection = diagram.onClusterSelected { select(it, centerNode = false) }

    init {
        diagram.routeNavigation = this
        diagram.pathHighlights.configureScopes(explorer.routeScopes)
        diagram.setStepActions(previous = { step(StepDirection.PREVIOUS) }, next = { step(StepDirection.NEXT) })
        render(centerNode = false)
    }

    fun focus(nodeId: String) = select(nodeId, centerNode = true)

    /** 直接聚焦展开上游视图；候选点击在进入此流程前处理。 */
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
        if (!ready(explorer.currentNodeId)) return
        if (hasChoice && direction == pendingDirection) return
        cancelChoice()
        onActivated()
        handle(if (direction == StepDirection.PREVIOUS) explorer.previous() else explorer.next(), direction, false)
    }

    /** 源码控件使用图中的候选，重复选择同一方向保留当前预览。 */
    fun stepFromSource(backward: Boolean) {
        step(if (backward) StepDirection.PREVIOUS else StepDirection.NEXT)
    }

    /** 已有补全按钮仍是显式操作，拥有独立的分阶段上游选择。 */
    fun complete(reselectNode: String? = null) {
        if (!ready(explorer.currentNodeId)) return
        cancelChoice()
        if (reselectNode != null && reselectNode != explorer.currentNodeId) explorer.jumpTo(reselectNode)
        onActivated()
        handle(explorer.completeRoute(reselect = true), StepDirection.PREVIOUS, true)
    }

    private fun handle(result: StepResult, direction: StepDirection, completion: Boolean) {
        when (result) {
            is StepResult.Moved -> {
                clearChoice()
                commit()
            }

            is StepResult.Choose -> showChoices(result, direction, completion)
            else -> cancelChoice()
        }
    }

    private fun showChoices(result: StepResult.Choose, direction: StepDirection, completion: Boolean) {
        clearChoice()
        pendingEdges = result.edges
        pendingDirection = direction
        completing = completion
        cursor = 0
        diagram.setChoiceTargets(result.edges.mapTo(linkedSetOf()) {
            if (direction == StepDirection.NEXT) it.to else it.from
        })
        diagram.routeChoice.setCandidates(result.edges, direction)
        previewEdge(result.edges.first().id)
        render(centerNode = false)
    }

    private fun choose(edgeId: String) {
        if (!ready(explorer.currentNodeId) || pendingEdges.none { it.id == edgeId }) return
        val direction = pendingDirection
        val completion = completing
        val result = if (completion) explorer.chooseIncoming(edgeId) else explorer.choose(edgeId, direction)
        handle(result, direction, completion)
    }

    /** 候选节点仅确认唯一关系，平行关系仍需明确选择。 */
    fun selectCandidate(nodeId: String): Boolean {
        if (!hasChoice || nodeId !in diagram.choiceTargets) return false
        if (!ready(explorer.currentNodeId)) return true
        val matches = pendingEdges.filter { targetOf(it) == nodeId }
        if (matches.size == 1) choose(matches.single().id)
        else previewEdge(matches.first().id)
        return true
    }

    /** 待选入边和当前节点的普通入边均可直接选择，焦点保持在目标节点。 */
    val clickableEdgeIds: Set<String> get() = if (hasChoice) diagram.routeChoice.edgeIds
        else explorer.availableEdges(StepDirection.PREVIOUS).mapTo(linkedSetOf()) { it.id }

    fun selectCandidateEdge(edgeId: String): Boolean {
        if (edgeId !in clickableEdgeIds || !ready(explorer.currentNodeId)) return false
        if (hasChoice) choose(edgeId)
        else {
            cancelChoice()
            onActivated()
            handle(explorer.completeViaIncoming(edgeId), StepDirection.PREVIOUS, true)
        }
        return true
    }

    fun previewEdge(edgeId: String?) {
        val edge = pendingEdges.firstOrNull { it.id == edgeId }
        if (edge != null) cursor = pendingEdges.indexOf(edge)
        diagram.routeChoice.focusedNodeId = edge?.let(::targetOf)
        diagram.routeChoice.focusedEdgeId = edge?.id?.takeIf { it in diagram.routeChoice.edgeIds }
        diagram.repaint()
    }

    fun previewCandidateNode(nodeId: String?) {
        val matching = pendingEdges.filter { targetOf(it) == nodeId }
        val current = matching.firstOrNull { it.id == pendingEdges.getOrNull(cursor)?.id } ?: matching.firstOrNull()
        previewEdge(current?.id)
    }

    private fun targetOf(edge: GraphEdge): String =
        if (pendingDirection == StepDirection.NEXT) edge.to else edge.from

    /** 外部源码反馈恢复前缀和后缀，不再次触发源码选择回调。 */
    fun synchronize(nodeId: String, state: BrowseState?): Boolean {
        if (!ready(nodeId)) return false
        if (nodeId == explorer.currentNodeId && state?.nodeId == nodeId &&
            (state.navigationState?.let { it == explorer.navigationState } ?: (state.staticPath == explorer.path))
        ) {
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
        cursor = (cursor + delta + pendingEdges.size) % pendingEdges.size
        previewEdge(pendingEdges[cursor].id)
        return true
    }

    fun confirmCandidate(): Boolean {
        if (!hasChoice) return false
        if (!ready(explorer.currentNodeId)) return true
        choose(pendingEdges[cursor.coerceIn(pendingEdges.indices)].id)
        return true
    }

    private fun commit() {
        render(centerNode = true)
        onActivated()
        onMoved(explorer)
    }

    fun refresh(center: Boolean = false) = render(center)

    private fun render(centerNode: Boolean) {
        diagram.pathHighlights.showNavigation(explorer.navigationState, explorer.currentNodeId, centerNode)
        diagram.setStepAvailability(
            explorer.canPrevious || (hasChoice && pendingDirection == StepDirection.PREVIOUS),
            explorer.canNext || (hasChoice && pendingDirection == StepDirection.NEXT),
        )
        diagram.controls.updateRoute(
            visible = explorer.canComplete || hasChoice, choosing = hasChoice,
            notice = explorer.routeNotice,
        ) { if (hasChoice) cancelChoice() else complete() }
    }

    private fun clearChoice() {
        pendingEdges = emptyList()
        diagram.routeChoice.clear()
        diagram.setChoiceTargets(emptySet())
    }

    /** 清除图中候选，但保留已确认路线和当前视口。 */
    fun cancelChoice() {
        val choosing = hasChoice
        if (choosing) diagram.cameraTimer.stop()
        clearChoice()
        explorer.cancelIncoming()
        if (choosing) diagram.queueViewportSave()
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
        onActivated = {}
        diagram.routeNavigation = null
        diagram.setStepActions(null, null)
        diagram.controls.updateRoute(visible = false, choosing = false, notice = null, action = null)
    }
}
