package com.master.understanding.diagram

import com.intellij.openapi.Disposable
import com.master.understanding.core.StaticExplorer
import com.master.understanding.model.BrowseState
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.ide.GraphNodePosition
import com.master.understanding.ide.GraphLayoutPreference
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.ToolTipManager
import kotlin.math.max
import kotlin.math.min

/** A single graph surface shared by the tool window and the editor. */
internal class UnderstandingDiagram(
    private val record: UnderstandingRecord,
    internal val initialState: BrowseState?,
    internal val clusterSpecs: List<DiagramCluster> = emptyList(),
    private val examplePanels: List<DiagramExamplePanel> = emptyList(),
    layoutComputer: (LayoutDirection) -> UnderstandingGraphLayout = { direction ->
        if (clusterSpecs.isEmpty()) layoutUnderstandingGraph(record.graph, direction)
        else layoutUnderstandingClusters(record.graph, clusterSpecs, examplePanels, direction)
    },
) : JPanel(), Disposable {
    private var automaticLayout = UnderstandingGraphLayout(emptyMap(), emptyList(),
        Rectangle2D.Double(0.0, 0.0, 220.0, 72.0))
    internal var layout = automaticLayout
    private val layoutCoordinator = DiagramLayoutCoordinator(layoutComputer, ::onLayoutResult)
    internal var layoutReady = false
        private set
    internal var layoutFailure: String? = null
        private set
    internal var activeDirection = LayoutDirection.VERTICAL
        private set
    var layoutPreference = GraphLayoutPreference.AUTO
        private set
    private val directionPositions = mutableMapOf<LayoutDirection, Map<String, GraphNodePosition>>()
    internal val directionViewports = mutableMapOf<LayoutDirection, BrowseState>()
    private var preferenceChanged: ((GraphLayoutPreference) -> Unit)? = null
    internal var nodePositions = emptyMap<String, GraphNodePosition>()
    private var nodePositionsChanged: ((Map<String, GraphNodePosition>) -> Unit)? = null
    private val callbacks = mutableSetOf<(String) -> Unit>()
    internal val questionCallbacks = mutableSetOf<(String) -> Unit>()
    internal val clusterCallbacks = mutableSetOf<(String) -> Unit>()
    internal val copyCallbacks = mutableSetOf<(String) -> Unit>()
    internal val addNoteCallbacks = mutableSetOf<(String) -> Unit>()
    internal val noteCallbacks = mutableSetOf<(String, java.awt.Component, java.awt.Point) -> Unit>()
    internal val titleCallbacks = mutableSetOf<(String) -> Unit>()
    internal var questionCounts = emptyMap<String, Int>()
    internal var pendingNoteCounts = emptyMap<String, Int>()
    private val startIds = record.graph.nodes.filter { node ->
        node.kind != "placeholder" && record.graph.edges.none { it.to == node.id }
    }.mapTo(mutableSetOf()) { it.id }
    private val endIds = record.graph.nodes.filter { node ->
        node.kind != "placeholder" && record.graph.edges.none { it.from == node.id }
    }.mapTo(mutableSetOf()) { it.id }
    internal var hoveredNodeId: String? = null
    internal var hoveredAction: DiagramNodeAction? = null
    internal var selectedNodeId = initialState?.nodeId ?: record.graph.nodes.firstOrNull()?.id
    internal var currentNodeId = selectedNodeId
    internal var routeEdgeIds = emptySet<String>()
    private var routeNodeIds = emptySet<String>()
    internal var choiceTargets = emptySet<String>()
    private var choicePhase = 0f
    internal var copiedNodeId: String? = null
    private var previousStep: (() -> Unit)? = null
    private var nextStep: (() -> Unit)? = null
    internal var flowPhase = 0f
    internal var animateFlow = true
    internal var initializedViewport = false
    internal var pendingCenterNodeId: String? = null
    internal var pendingFocusClusterId: String? = null
    internal var disposed = false
    private var internalZoom = 1.0
    private var pinChanged: ((Boolean) -> Unit)? = null
    private var followChanged: ((Boolean) -> Unit)? = null
    private var flowChanged: ((Boolean) -> Unit)? = null
    private var viewportChanged: (() -> Unit)? = null
    private var stateSubscription: AutoCloseable? = null

    val jComponent: JComponent get() = this
    val center: Point2D.Double get() = Point2D.Double(centerX, centerY)
    var centerX: Double = layout.bounds.centerX
        internal set
    var centerY: Double = layout.bounds.centerY
        internal set
    internal var hasPainted = false
    internal val cameraMotion = UnderstandingCameraMotion(centerX, centerY)
    var zoom: Double
        get() = internalZoom
        set(value) {
            if (!value.isFinite()) return
            internalZoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
            repaint()
            queueViewportSave()
        }
    var flowEnabled: Boolean
        get() = animateFlow
        set(value) {
            if (animateFlow == value) return
            animateFlow = value
            updateTimer()
            repaint()
            flowChanged?.invoke(value)
            controls.refreshBehavior()
        }
    var navigationPinned: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            pinChanged?.invoke(value)
            controls.refreshBehavior()
        }
    var autoFollowNode: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) currentNodeId?.let(::centerOnNode)
            else {
                cameraTimer.stop()
                pendingCenterNodeId = null
            }
            followChanged?.invoke(value)
            controls.refreshBehavior()
        }

    internal val flowTimer = Timer(45) { advanceFlow() }
    internal val choiceTimer = Timer(80) {
        choicePhase += 0.16f
        choiceTargets.mapNotNull { layout.nodes[it]?.bounds }.forEach(::repaintGraphArea)
    }
    internal val copyTimer = Timer(1800) { copiedNodeId = null; repaint() }.apply { isRepeats = false }
    internal val cameraTimer = Timer(16) { advanceCamera() }
    private val controls = UnderstandingDiagramControls(this)
    internal val clusters: List<PlacedCluster> get() = layout.clusters
    internal val clusterCount: Int get() = clusterSpecs.size +
        if (record.graph.nodes.any { node -> clusterSpecs.none { node.id in it.nodeIds } }) 1 else 0

    fun setStepActions(previous: (() -> Unit)?, next: (() -> Unit)?) {
        previousStep = previous
        nextStep = next
        controls.updateSteps(previous != null, next != null)
    }

    fun setStepAvailability(hasPrevious: Boolean, hasNext: Boolean) {
        controls.updateStepAvailability(hasPrevious, hasNext)
    }

    fun setHierarchy(items: List<DiagramHierarchyItem>, onNavigate: (Int) -> Unit) {
        controls.setHierarchy(items, onNavigate)
        revalidate()
        repaint()
    }

    fun setNodePositions(positions: Map<String, GraphNodePosition>) {
        if (!layoutReady) {
            directionPositions[activeDirection] = positions
            nodePositions = positions
            return
        }
        val exampleIds = automaticLayout.clusters.filter { it.exampleBounds != null }
            .mapTo(mutableSetOf()) { examplePositionKey(it.id) }
        val valid = positions.filter { (id, point) ->
            (id in automaticLayout.nodes || id in exampleIds) && point.x.isFinite() && point.y.isFinite()
        }
        if (nodePositions == valid) return
        nodePositions = valid
        directionPositions[activeDirection] = valid
        layout = positionUnderstandingNodes(record.graph, automaticLayout,
            valid.mapValues { (_, p) -> Point2D.Double(p.x, p.y) })
        repaint()
    }

    fun onNodePositionsChanged(callback: (Map<String, GraphNodePosition>) -> Unit) {
        nodePositionsChanged = callback
    }

    internal fun commitNodePositions() {
        nodePositionsChanged?.invoke(nodePositions)
    }

    fun arrangeNodes() {
        if (nodePositions.isNotEmpty()) {
            setNodePositions(emptyMap())
            nodePositionsChanged?.invoke(emptyMap())
        }
        requestLayout()
    }

    fun setLayoutPreference(value: GraphLayoutPreference, persist: Boolean = true) {
        if (layoutPreference == value) return
        if (persist && layoutReady) viewportChanged?.invoke()
        layoutPreference = value
        if (persist) preferenceChanged?.invoke(value)
        controls.refreshBehavior()
        requestLayout()
    }

    fun onLayoutPreferenceChanged(callback: (GraphLayoutPreference) -> Unit) {
        preferenceChanged = callback
    }

    fun setDirectionState(direction: LayoutDirection, viewport: BrowseState?,
        positions: Map<String, GraphNodePosition>) {
        if (viewport != null) directionViewports[direction] = viewport
        directionPositions[direction] = positions
        if (layoutReady && activeDirection == direction) setNodePositions(positions)
    }

    fun retryLayout() { requestLayout() }

    private fun requestedDirection(): LayoutDirection = when (layoutPreference) {
        GraphLayoutPreference.VERTICAL -> LayoutDirection.VERTICAL
        GraphLayoutPreference.HORIZONTAL -> LayoutDirection.HORIZONTAL
        GraphLayoutPreference.AUTO -> if (width < 800) LayoutDirection.VERTICAL else LayoutDirection.HORIZONTAL
    }

    internal fun requestLayout() {
        if (disposed || width <= 0 || height <= 0) return
        layoutFailure = null
        layoutCoordinator.request(requestedDirection())
        repaint()
    }

    private fun onLayoutResult(direction: LayoutDirection, result: Result<UnderstandingGraphLayout>) {
        result.onSuccess { computed ->
            automaticLayout = computed
            activeDirection = direction
            layoutReady = true
            layout = automaticLayout
            nodePositions = emptyMap()
            setNodePositions(directionPositions[activeDirection].orEmpty())
            if (!initializedViewport) initializeViewport()
            else {
                directionViewports[activeDirection]?.let(::restoreViewport)
                    ?: currentNodeId?.let(::centerOnNode)
            }
            controls.refreshClusters()
            repaint()
        }.onFailure { failure ->
            layoutFailure = failure.message ?: failure.javaClass.simpleName
            repaint()
        }
    }

    internal fun previousStep() { previousStep?.invoke() }
    internal fun nextStep() { nextStep?.invoke() }

    fun onPinChanged(callback: (Boolean) -> Unit) { pinChanged = callback }
    fun onAutoFollowChanged(callback: (Boolean) -> Unit) { followChanged = callback }
    fun onFlowChanged(callback: (Boolean) -> Unit) { flowChanged = callback }
    fun onViewportChanged(callback: () -> Unit) { viewportChanged = callback }
    fun bindStateSubscription(subscription: AutoCloseable) { stateSubscription = subscription }

    private val viewportSaveTimer = Timer(200) { viewportChanged?.invoke() }.apply { isRepeats = false }
    internal fun queueViewportSave() {
        if (viewportChanged != null) viewportSaveTimer.restart()
    }

    fun setChoiceTargets(targets: Set<String>) {
        choiceTargets = targets.filterTo(mutableSetOf()) { it in layout.nodes }
        updateTimer()
        if (autoFollowNode && choiceTargets.isNotEmpty() && width > 0 && height > 0) {
            val bounds = Rectangle2D.Double()
            val candidates = (choiceTargets + listOfNotNull(currentNodeId)).mapNotNull { layout.nodes[it]?.bounds }
            candidates.firstOrNull()?.let(bounds::setRect)
            candidates.drop(1).forEach(bounds::add)
            val targetZoom = min((width - 64.0) / max(bounds.width, 1.0),
                (height - controls.height - 72.0) / max(bounds.height, 1.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (targetZoom < zoom) zoom = targetZoom
            moveCameraTo(bounds.centerX, bounds.centerY)
        }
        repaint()
    }

    fun showCopied(nodeId: String) {
        copiedNodeId = nodeId
        copyTimer.restart()
        repaint()
    }

    init {
        setLayout(null)
        add(controls)
        add(controls.sideControls)
        add(controls.previousButton)
        add(controls.nextButton)
        isOpaque = true
        isFocusable = true
        ToolTipManager.sharedInstance().registerComponent(this)
        preferredSize = Dimension(520, 360)
        getAccessibleContext().accessibleName = "理解图"
        getAccessibleContext().accessibleDescription = "方向键选择节点，Ctrl+C 复制来源，Enter 查看追问"
        DiagramKeyboardActions.install(this, ::moveSelection,
            onCopy = { currentNodeId?.let { id -> copyCallbacks.toList().forEach { it(id) } } },
            onQuestions = {
                currentNodeId?.takeIf { (questionCounts[it] ?: 0) > 0 }?.let { id ->
                    questionCallbacks.toList().forEach { it(id) }
                }
            })
        val mouse = DiagramMouseInteraction(this)
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (!initializedViewport && width > 0 && height > 0) initializeViewport()
                pendingFocusClusterId?.let { id ->
                    pendingFocusClusterId = null
                    focusCluster(id, animate = false)
                }
                if (autoFollowNode) pendingCenterNodeId?.let { centerOnNode(it) }
            }
        })
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) updateTimer()
        }
        if (record.graph.nodes.isNotEmpty()) {
            val explorer = StaticExplorer(record.graph, initialState?.staticPath.orEmpty(), initialState?.nodeId)
            showUnderstandingPath(explorer.path, explorer.currentNodeId, centerNode = false)
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        copyActionAt(event.point)?.let { return "复制" }
        questionActionAt(event.point)?.let { return "查看 ${questionCounts.getValue(it)} 个追问" }
        return nodeAt(event.point)?.takeIf { it == hoveredNodeId }?.let { id ->
            layout.nodes[id]?.node?.label
        }
    }

    internal fun retryFailedLayout(): Boolean {
        if (layoutFailure == null) return false
        retryLayout()
        return true
    }


    private fun moveSelection(delta: Int) {
        val ids = layout.nodes.keys.toList()
        if (ids.isEmpty()) return
        val index = ids.indexOf(currentNodeId).coerceAtLeast(0)
        selectUnderstandingNode(ids[(index + delta + ids.size) % ids.size], notify = true)
    }

    override fun doLayout() {
        controls.arrange(width)
        controls.arrangeSide(width, height)
        controls.arrangeSteps(width, height)
        super.doLayout()
    }

    fun onNodeSelected(callback: (String) -> Unit): AutoCloseable {
        callbacks.add(callback)
        return AutoCloseable { callbacks.remove(callback) }
    }

    fun onQuestionBadgeClicked(callback: (String) -> Unit): AutoCloseable {
        questionCallbacks.add(callback)
        return AutoCloseable { questionCallbacks.remove(callback) }
    }

    fun onClusterSelected(callback: (String) -> Unit): AutoCloseable {
        clusterCallbacks.add(callback)
        return AutoCloseable { clusterCallbacks.remove(callback) }
    }

    fun onCopyNodeContext(callback: (String) -> Unit): AutoCloseable {
        copyCallbacks.add(callback)
        return AutoCloseable { copyCallbacks.remove(callback) }
    }

    fun onAddNodeNote(callback: (String) -> Unit): AutoCloseable {
        addNoteCallbacks += callback
        return AutoCloseable { addNoteCallbacks -= callback }
    }

    fun onNoteBadgeClicked(callback: (String, java.awt.Component, java.awt.Point) -> Unit): AutoCloseable {
        noteCallbacks += callback
        return AutoCloseable { noteCallbacks -= callback }
    }

    fun onClusterTitleEdit(callback: (String) -> Unit): AutoCloseable {
        titleCallbacks += callback
        return AutoCloseable { titleCallbacks -= callback }
    }

    fun setPendingNoteCounts(counts: Map<String, Int>) {
        val ids = record.graph.nodes.mapTo(hashSetOf()) { it.id }
        pendingNoteCounts = counts.filter { (id, count) -> id in ids && count > 0 }
        repaint()
    }

    internal fun showNodeMenu(nodeId: String, point: java.awt.Point) {
        JPopupMenu().apply {
            add(JMenuItem("添加注释").apply {
                isEnabled = addNoteCallbacks.isNotEmpty()
                addActionListener { addNoteCallbacks.toList().forEach { it(nodeId) } }
            })
            show(this@UnderstandingDiagram, point.x, point.y)
        }
    }

    fun setQuestionCounts(counts: Map<String, Int>) {
        val ids = record.graph.nodes.mapTo(hashSetOf()) { it.id }
        questionCounts = counts.filter { (id, count) -> id in ids && count > 0 }
        repaint()
    }

    fun showUnderstandingPath(pathEdgeIds: List<String>, nodeId: String, centerNode: Boolean) {
        routeEdgeIds = pathEdgeIds.toSet()
        routeNodeIds = record.graph.edges.filter { it.id in routeEdgeIds }
            .flatMap { listOf(it.from, it.to) }.toSet()
        currentNodeId = nodeId
        selectedNodeId = nodeId
        if (centerNode && autoFollowNode) centerOnNode(nodeId)
        updateTimer()
        repaint()
    }

    fun selectUnderstandingNode(nodeId: String) = selectUnderstandingNode(nodeId, notify = false)

    internal fun selectUnderstandingNode(nodeId: String, notify: Boolean) {
        if (nodeId !in layout.nodes) return
        if (notify && choiceTargets.isNotEmpty() && nodeId !in choiceTargets) return
        selectedNodeId = nodeId
        currentNodeId = nodeId
        if (autoFollowNode) centerOnNode(nodeId)
        if (notify) callbacks.toList().forEach { it(nodeId) }
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        DiagramCanvasPainter.paint(graphics, DiagramPaintState(layout, examplePanels, width, height,
            zoom, centerX, centerY, currentNodeId, hoveredNodeId, hoveredAction, questionCounts,
            pendingNoteCounts,
            startIds, endIds, routeEdgeIds, choiceTargets, choicePhase, copiedNodeId,
            animateFlow, flowPhase, controls.height))
        if (!layoutReady || layoutFailure != null) {
            val message = layoutFailure?.let { "布局失败：$it（点击重试）" } ?: "正在排版…"
            graphics.color = foreground
            graphics.drawString(message, 18, height - 22)
        }
        hasPainted = true
    }

    override fun dispose() {
        disposed = true
        controls.dispose()
        layoutCoordinator.close()
        flowTimer.stop()
        choiceTimer.stop()
        copyTimer.stop()
        cameraTimer.stop()
        viewportSaveTimer.stop()
        stateSubscription?.close()
        stateSubscription = null
        pinChanged = null
        followChanged = null
        flowChanged = null
        viewportChanged = null
        nodePositionsChanged = null
        preferenceChanged = null
        callbacks.clear()
        questionCallbacks.clear()
        clusterCallbacks.clear()
        copyCallbacks.clear()
        addNoteCallbacks.clear()
        noteCallbacks.clear()
        titleCallbacks.clear()
    }

    companion object {
        internal const val MIN_ZOOM = 0.02
        internal const val MAX_ZOOM = 4.0
        internal const val DASH_CYCLE = 20f
    }
}
