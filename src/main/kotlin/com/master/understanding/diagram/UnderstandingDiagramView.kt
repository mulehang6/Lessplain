package com.master.understanding.diagram

import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.master.understanding.core.StaticExplorer
import com.master.understanding.model.BrowseState
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.ide.GraphNodePosition
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.abs

/** A single graph surface shared by the tool window and the editor. */
internal class UnderstandingDiagram(
    private val record: UnderstandingRecord,
    private val initialState: BrowseState?,
    private val clusterSpecs: List<DiagramCluster> = emptyList(),
    private val examplePanels: List<DiagramExamplePanel> = emptyList(),
) : JPanel(), Disposable {
    private val automaticLayout = if (clusterSpecs.isEmpty()) layoutUnderstandingGraph(record.graph)
        else layoutUnderstandingClusters(record.graph, clusterSpecs, examplePanels)
    private var layout = automaticLayout
    private var nodePositions = emptyMap<String, GraphNodePosition>()
    private var nodePositionsChanged: ((Map<String, GraphNodePosition>) -> Unit)? = null
    private val callbacks = mutableSetOf<(String) -> Unit>()
    private val questionCallbacks = mutableSetOf<(String) -> Unit>()
    private val clusterCallbacks = mutableSetOf<(String) -> Unit>()
    private val copyCallbacks = mutableSetOf<(String) -> Unit>()
    private var questionCounts = emptyMap<String, Int>()
    private val startIds = record.graph.nodes.filter { node ->
        node.kind != "placeholder" && record.graph.edges.none { it.to == node.id }
    }.mapTo(mutableSetOf()) { it.id }
    private val endIds = record.graph.nodes.filter { node ->
        node.kind != "placeholder" && record.graph.edges.none { it.from == node.id }
    }.mapTo(mutableSetOf()) { it.id }
    private var hoveredNodeId: String? = null
    private var hoveredAction: NodeAction? = null
    private var selectedNodeId = initialState?.nodeId ?: record.graph.nodes.firstOrNull()?.id
    private var currentNodeId = selectedNodeId
    private var routeEdgeIds = emptySet<String>()
    private var routeNodeIds = emptySet<String>()
    private var choiceTargets = emptySet<String>()
    private var choicePhase = 0f
    private var copiedNodeId: String? = null
    private var previousStep: (() -> Unit)? = null
    private var nextStep: (() -> Unit)? = null
    private var flowPhase = 0f
    private var animateFlow = true
    private var initializedViewport = false
    private var pendingCenterNodeId: String? = null
    private var pressPoint: Point? = null
    private var previousDragPoint: Point? = null
    private var draggedNodeId: String? = null

    private var draggedExampleClusterId: String? = null
    private var dragged = false
    private var disposed = false
    private var internalZoom = 1.0
    private var pinChanged: ((Boolean) -> Unit)? = null
    private var followChanged: ((Boolean) -> Unit)? = null
    private var flowChanged: ((Boolean) -> Unit)? = null
    private var viewportChanged: (() -> Unit)? = null
    private var stateSubscription: AutoCloseable? = null

    val jComponent: JComponent get() = this
    val center: Point2D.Double get() = Point2D.Double(centerX, centerY)
    var centerX: Double = layout.bounds.centerX
        private set
    var centerY: Double = layout.bounds.centerY
        private set
    private var hasPainted = false
    private val cameraMotion = UnderstandingCameraMotion(centerX, centerY)
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
            controls.updateFlow(value)
            updateTimer()
            repaint()
            flowChanged?.invoke(value)
        }
    var navigationPinned: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            controls.updatePinned(value)
            pinChanged?.invoke(value)
        }
    var autoFollowNode: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            controls.updateAutoFollow(value)
            if (value) currentNodeId?.let(::centerOnNode)
            else {
                cameraTimer.stop()
                pendingCenterNodeId = null
            }
            followChanged?.invoke(value)
        }

    private val flowTimer = Timer(45) { advanceFlow() }
    private val choiceTimer = Timer(80) { choicePhase += 0.16f; repaint() }
    private val copyTimer = Timer(1800) { copiedNodeId = null; repaint() }.apply { isRepeats = false }
    private val cameraTimer = Timer(16) { advanceCamera() }
    private val controls = UnderstandingDiagramControls(this)
    internal val clusters: List<PlacedCluster> get() = layout.clusters

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
        val exampleIds = automaticLayout.clusters.filter { it.exampleBounds != null }
            .mapTo(mutableSetOf()) { examplePositionKey(it.id) }
        val valid = positions.filter { (id, point) ->
            (id in automaticLayout.nodes || id in exampleIds) && point.x.isFinite() && point.y.isFinite()
        }
        if (nodePositions == valid) return
        nodePositions = valid
        layout = positionUnderstandingNodes(record.graph, automaticLayout,
            valid.mapValues { (_, p) -> Point2D.Double(p.x, p.y) })
        repaint()
    }

    fun onNodePositionsChanged(callback: (Map<String, GraphNodePosition>) -> Unit) {
        nodePositionsChanged = callback
    }

    fun arrangeNodes() {
        if (nodePositions.isNotEmpty()) {
            setNodePositions(emptyMap())
            nodePositionsChanged?.invoke(emptyMap())
        }
        fitContent()
    }

    internal fun previousStep() { previousStep?.invoke() }
    internal fun nextStep() { nextStep?.invoke() }

    fun onPinChanged(callback: (Boolean) -> Unit) { pinChanged = callback }
    fun onAutoFollowChanged(callback: (Boolean) -> Unit) { followChanged = callback }
    fun onFlowChanged(callback: (Boolean) -> Unit) { flowChanged = callback }
    fun onViewportChanged(callback: () -> Unit) { viewportChanged = callback }
    fun bindStateSubscription(subscription: AutoCloseable) { stateSubscription = subscription }

    private val viewportSaveTimer = Timer(200) { viewportChanged?.invoke() }.apply { isRepeats = false }
    private fun queueViewportSave() {
        if (viewportChanged != null) viewportSaveTimer.restart()
    }

    fun setChoiceTargets(targets: Set<String>) {
        choiceTargets = targets.filterTo(mutableSetOf()) { it in layout.nodes }
        if (choiceTargets.isEmpty()) choiceTimer.stop() else if (isShowing) choiceTimer.start()
        if (autoFollowNode && choiceTargets.isNotEmpty() && width > 0 && height > 0) {
            val bounds = java.awt.geom.Rectangle2D.Double()
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

    internal fun advanceFlow() {
        flowPhase = (flowPhase - 1.5f + DASH_CYCLE) % DASH_CYCLE
        repaint()
    }

    init {
        setLayout(null)
        add(controls)
        add(controls.previousButton)
        add(controls.nextButton)
        isOpaque = true
        isFocusable = true
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
        val mouse = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1) return
                requestFocusInWindow()
                pressPoint = event.point
                previousDragPoint = event.point
                draggedNodeId = null
                draggedExampleClusterId = null
                if (copyActionAt(event.point) == null && questionActionAt(event.point) == null) {
                    draggedNodeId = nodeAt(event.point)
                    if (draggedNodeId == null) draggedExampleClusterId = examplePanelAt(event.point)
                    if (draggedExampleClusterId == null) draggedNodeId = draggableNodeAt(event.point)
                }
                dragged = false
            }

            override fun mouseDragged(event: MouseEvent) {
                val previous = previousDragPoint ?: return
                if ((pressPoint?.distance(event.point) ?: 0.0) > 3.0) dragged = true
                if (!dragged) return
                cameraTimer.stop()
                val nodeId = draggedNodeId
                if (nodeId != null) {
                    val b = layout.nodes[nodeId]?.bounds ?: return
                    val position = GraphNodePosition(b.x + (event.x - previous.x) / zoom,
                        b.y + (event.y - previous.y) / zoom)
                    setNodePositions(nodePositions + (nodeId to position))
                } else if (draggedExampleClusterId != null) {
                    val clusterId = draggedExampleClusterId ?: return
                    val b = layout.clusters.firstOrNull { it.id == clusterId }?.exampleBounds ?: return
                    val position = GraphNodePosition(b.x + (event.x - previous.x) / zoom,
                        b.y + (event.y - previous.y) / zoom)
                    setNodePositions(nodePositions + (examplePositionKey(clusterId) to position))
                } else {
                    centerX -= (event.x - previous.x) / zoom
                    centerY -= (event.y - previous.y) / zoom
                }
                previousDragPoint = event.point
                repaint()
            }

            override fun mouseReleased(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && !dragged) {
                    val copyNode = copyActionAt(event.point)
                    val questionNode = questionActionAt(event.point)
                    if (copyNode != null) copyCallbacks.toList().forEach { it(copyNode) }
                    else if (questionNode != null) questionCallbacks.toList().forEach { it(questionNode) }
                    else nodeAt(event.point)?.let { selectUnderstandingNode(it, notify = true) }
                }
                previousDragPoint = null
                pressPoint = null
                if (dragged) {
                    if (draggedNodeId != null || draggedExampleClusterId != null)
                        nodePositionsChanged?.invoke(nodePositions)
                    else queueViewportSave()
                }
                draggedNodeId = null
                draggedExampleClusterId = null
            }

            override fun mouseMoved(event: MouseEvent) {
                val hover = hoverNodeAt(event.point)
                val oldHover = hoveredNodeId
                val oldAction = hoveredAction
                hoveredNodeId = hover
                hoveredAction = when {
                    copyActionAt(event.point) != null -> NodeAction.COPY
                    questionActionAt(event.point) != null -> NodeAction.QUESTIONS
                    else -> null
                }
                if (oldHover != hover || oldAction != hoveredAction) {
                    hoveredNodeId = hover
                    repaint()
                }
                cursor = if (hover != null || examplePanelAt(event.point) != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else Cursor.getDefaultCursor()
            }

            override fun mouseExited(event: MouseEvent) {
                hoveredNodeId = null
                hoveredAction = null
                repaint()
            }

            override fun mouseWheelMoved(event: MouseWheelEvent) {
                cameraTimer.stop()
                val before = screenToWorld(event.point)
                zoom *= 1.12.pow(-event.preciseWheelRotation)
                val after = screenToWorld(event.point)
                centerX += before.x - after.x
                centerY += before.y - after.y
                repaint()
                queueViewportSave()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (!initializedViewport && width > 0 && height > 0) initializeViewport()
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
        // TODO: Use the IDE's code preview API to show the hovered node's source when this interaction is added.
        return null
    }


    private fun moveSelection(delta: Int) {
        val ids = layout.nodes.keys.toList()
        if (ids.isEmpty()) return
        val index = ids.indexOf(currentNodeId).coerceAtLeast(0)
        selectUnderstandingNode(ids[(index + delta + ids.size) % ids.size], notify = true)
    }

    override fun doLayout() {
        controls.arrange(width)
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

    fun setQuestionCounts(counts: Map<String, Int>) {
        questionCounts = counts.filter { (id, count) -> id in layout.nodes && count > 0 }
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

    private fun selectUnderstandingNode(nodeId: String, notify: Boolean) {
        if (nodeId !in layout.nodes) return
        if (notify && choiceTargets.isNotEmpty() && nodeId !in choiceTargets) return
        selectedNodeId = nodeId
        currentNodeId = nodeId
        if (autoFollowNode) centerOnNode(nodeId)
        if (notify) callbacks.toList().forEach { it(nodeId) }
        repaint()
    }

    fun fitContent() {
        if (width <= 0 || height <= 0) return
        cameraTimer.stop()
        val bounds = layout.bounds
        zoom = min(width.toDouble() / max(bounds.width, 1.0),
            height.toDouble() / max(bounds.height, 1.0))
            .coerceIn(MIN_ZOOM, MAX_ZOOM)
        centerX = bounds.centerX
        centerY = bounds.centerY
        repaint()
        queueViewportSave()
    }

    private fun initializeViewport() {
        initializedViewport = true
        val saved = initialState
        if (saved != null && saved.hasPan && saved.panX.isFinite() && saved.panY.isFinite()) {
            zoom = saved.zoom
            centerX = saved.panX
            centerY = saved.panY
        } else if (layout.clusters.isNotEmpty()) {
            focusCluster(layout.clusters.first().id, animate = false)
            return
        } else {
            val focus = currentNodeId?.let(layout.nodes::get)?.bounds
            if (focus == null) fitContent() else {
                zoom = min(1.0, min(width / (focus.width + 180), height / (focus.height + 180)))
                centerX = focus.centerX
                centerY = focus.centerY
            }
        }
        repaint()
    }

    private fun centerOnNode(nodeId: String) {
        val bounds = layout.nodes[nodeId]?.bounds ?: return
        if (width <= 0 || height <= 0) {
            pendingCenterNodeId = nodeId
            return
        }
        pendingCenterNodeId = null
        moveCameraTo(bounds.centerX, bounds.centerY)
    }

    fun focusCluster(id: String, animate: Boolean = true) {
        val cluster = layout.clusters.firstOrNull { it.id == id } ?: return
        if (width <= 0 || height <= 0) return
        val targetZoom = min((width - 24).toDouble() / cluster.bounds.width,
            (height - 24).toDouble() / cluster.bounds.height).coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoom = targetZoom
        val x = cluster.bounds.centerX
        val y = cluster.bounds.centerY
        if (animate) moveCameraTo(x, y) else {
            centerX = x
            centerY = y
            repaint()
            queueViewportSave()
        }
        selectedNodeId = clusterSpecs.firstOrNull { it.id == id }?.nodeIds?.firstOrNull()
        currentNodeId = selectedNodeId
        if (animate) selectedNodeId?.let { node -> clusterCallbacks.toList().forEach { it(node) } }
        repaint()
    }

    private fun moveCameraTo(x: Double, y: Double) {
        if (cameraTimer.isRunning && abs(cameraMotion.targetX - x) < 0.5 && abs(cameraMotion.targetY - y) < 0.5) return
        cameraTimer.stop()
        cameraMotion.retarget(centerX, centerY, x, y, System.nanoTime())
        if (!isShowing || !hasPainted || java.lang.Boolean.getBoolean("master.understanding.reduceMotion") ||
            (abs(centerX - x) < 0.5 && abs(centerY - y) < 0.5)
        ) {
            centerX = x
            centerY = y
            repaint()
            queueViewportSave()
            return
        }
        cameraTimer.start()
    }

    private fun advanceCamera() {
        val now = System.nanoTime()
        val position = cameraMotion.positionAt(now)
        centerX = position.x
        centerY = position.y
        if (cameraMotion.isFinished(now)) {
            cameraTimer.stop()
            queueViewportSave()
        }
        repaint()
    }

    private fun screenToWorld(point: Point): Point2D.Double = Point2D.Double(
        centerX + (point.x - width / 2.0) / zoom,
        centerY + (point.y - height / 2.0) / zoom,
    )

    private fun nodeAt(point: Point): String? {
        val world = screenToWorld(point)
        return layout.nodes.values.firstOrNull { it.bounds.contains(world) }?.node?.id
    }

    private fun examplePanelAt(point: Point): String? {
        val world = screenToWorld(point)
        return layout.clusters.firstOrNull { it.exampleBounds?.contains(world) == true }?.id
    }

    private fun draggableNodeAt(point: Point): String? {
        nodeAt(point)?.let { return it }
        val world = screenToWorld(point)
        val padding = 9.0 / zoom
        return layout.nodes.values.asSequence().filter { placed ->
            val b = placed.bounds
            world.x >= b.minX - padding && world.x <= b.maxX + padding &&
                world.y >= b.minY - padding && world.y <= b.maxY + padding
        }.minByOrNull { placed ->
            val b = placed.bounds
            val dx = (world.x - b.centerX) / b.width
            val dy = (world.y - b.centerY) / b.height
            dx * dx + dy * dy
        }?.node?.id
    }

    private fun hoverNodeAt(point: Point): String? {
        val world = screenToWorld(point)
        val padding = 9.0 / zoom
        val actionEnd = 40.0
        // Keep the hover active through the gap between the node and its action buttons.
        hoveredNodeId?.let { id ->
            layout.nodes[id]?.bounds?.let { bounds ->
                if (world.x >= bounds.x - padding && world.x <= bounds.maxX + padding &&
                    world.y >= bounds.y - padding && world.y <= bounds.maxY + actionEnd + padding) return id
            }
        }
        return layout.nodes.values.firstOrNull { placed ->
            val bounds = placed.bounds
            world.x >= bounds.x - padding && world.x <= bounds.maxX + padding &&
                world.y >= bounds.y - padding && world.y <= bounds.maxY + padding
        }?.node?.id
    }

    private fun copyActionAt(point: Point): String? {
        val world = screenToWorld(point)
        val id = hoveredNodeId ?: return null
        return id.takeIf { layout.nodes[it]?.bounds?.let { bounds ->
            DiagramNodePainter.copyBounds(bounds).contains(world)
        } == true }
    }

    private fun questionActionAt(point: Point): String? {
        val world = screenToWorld(point)
        val id = hoveredNodeId?.takeIf { (questionCounts[it] ?: 0) > 0 } ?: return null
        return id.takeIf { layout.nodes[it]?.bounds?.let { bounds ->
            DiagramNodePainter.questionBounds(bounds).contains(world)
        } == true }
    }

    private fun updateTimer() {
        if (!disposed && animateFlow && isShowing && routeEdgeIds.isNotEmpty()) flowTimer.start() else flowTimer.stop()
        if (!disposed && isShowing && choiceTargets.isNotEmpty()) choiceTimer.start() else choiceTimer.stop()
        if (!isShowing && cameraTimer.isRunning) {
            cameraTimer.stop()
            centerX = cameraMotion.targetX
            centerY = cameraMotion.targetY
        }
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = BACKGROUND
            g.fillRect(0, 0, width, height)
            g.translate(width / 2.0, height / 2.0)
            g.scale(zoom, zoom)
            g.translate(-centerX, -centerY)
            paintClusters(g)
            layout.clusters.forEach { cluster ->
                val panel = examplePanels.firstOrNull { it.clusterId == cluster.id }
                if (panel != null && cluster.exampleBounds != null) {
                    DiagramExamplePainter.paint(g, cluster.exampleBounds, panel, currentNodeId)
                }
            }
            DiagramEdgePainter.paint(g, layout.edges, routeEdgeIds, animateFlow, flowPhase)
            DiagramNodePainter.paint(g, layout, currentNodeId, hoveredNodeId, questionCounts, startIds, endIds,
                choiceTargets, choicePhase, copiedNodeId)
            if (choiceTargets.isNotEmpty()) {
                val overlay = graphics.create() as Graphics2D
                try {
                    overlay.color = LABEL_COLOR
                    overlay.drawString("请选择闪烁的下一步节点", 16, controls.height + 34)
                } finally { overlay.dispose() }
            }
            paintActionTooltip(graphics)
            hasPainted = true
        } finally {
            g.dispose()
        }
    }

    private fun paintActionTooltip(graphics: Graphics) {
        val id = hoveredNodeId ?: return
        val action = hoveredAction ?: return
        val bounds = layout.nodes[id]?.bounds ?: return
        val actionBounds = if (action == NodeAction.COPY) DiagramNodePainter.copyBounds(bounds)
            else DiagramNodePainter.questionBounds(bounds)
        val label = if (action == NodeAction.COPY) "复制" else "查看 ${questionCounts[id] ?: 0} 个追问"
        val g = graphics.create() as Graphics2D
        try {
            g.font = UIManager.getFont("ToolTip.font") ?: g.font
            val metrics = g.fontMetrics
            val w = metrics.stringWidth(label) + 16
            val h = metrics.height + 8
            val x = (width / 2.0 + (actionBounds.centerX - centerX) * zoom - w / 2).toInt()
                .coerceIn(0, (width - w).coerceAtLeast(0))
            val y = (height / 2.0 + (actionBounds.y - centerY) * zoom - h - 5).toInt()
                .coerceIn(0, (height - h).coerceAtLeast(0))
            g.color = UIManager.getColor("ToolTip.background") ?: JBColor(Color(0xF1F1F1), Color(0x33363D))
            g.fillRoundRect(x, y, w, h, 8, 8)
            g.color = UIManager.getColor("ToolTip.foreground") ?: JBColor(Color.BLACK, Color.WHITE)
            g.drawString(label, x + 8, y + 4 + metrics.ascent)
        } finally { g.dispose() }
    }

    private fun paintClusters(g: Graphics2D) {
        layout.clusters.forEach { cluster ->
            val b = cluster.bounds
            g.color = CLUSTER_FILL
            g.fillRoundRect(b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), 18, 18)
            g.color = CLUSTER_BORDER
            g.stroke = BasicStroke(2f)
            g.drawRoundRect(b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), 18, 18)
            g.color = LABEL_COLOR
            val title = cluster.title.take(64)
            g.drawString(title, (b.x + 18).toFloat(), (b.y + 28).toFloat())
        }
    }

    override fun dispose() {
        disposed = true
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
        callbacks.clear()
        questionCallbacks.clear()
        clusterCallbacks.clear()
        copyCallbacks.clear()
    }

    companion object {
        private const val MIN_ZOOM = 0.02
        private const val MAX_ZOOM = 4.0
        private const val DASH_CYCLE = 20f
        private val BACKGROUND = JBColor(Color(0xFAFBFC), Color(0x1E1F22))
        private val CLUSTER_FILL = JBColor(Color(0xF5F8FC), Color(0x24272D))
        private val CLUSTER_BORDER = JBColor(Color(0x9EACC0), Color(0x788494))
        private val LABEL_COLOR = JBColor(Color(0x374151), Color(0xC4CBD5))
    }

    private enum class NodeAction { COPY, QUESTIONS }
}

internal fun createUnderstandingDiagram(
    record: UnderstandingRecord,
    initialState: BrowseState? = null,
    clusters: List<DiagramCluster> = emptyList(),
    examplePanels: List<DiagramExamplePanel> = emptyList(),
): UnderstandingDiagram = UnderstandingDiagram(record, initialState, clusters, examplePanels)
