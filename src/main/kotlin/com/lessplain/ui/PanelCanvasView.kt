package com.lessplain.ui

import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import com.lessplain.core.StaticExplorer
import com.lessplain.diagram.DiagramHierarchyItem
import com.lessplain.diagram.bindDiagramSourceNavigation
import com.lessplain.diagram.bindGraphState
import com.lessplain.diagram.bindNodeContextCopy
import com.lessplain.diagram.createUnderstandingDiagram
import com.lessplain.diagram.graphBrowseState
import com.lessplain.diagram.requestFocusCluster
import com.lessplain.ide.CanvasDraftHost
import com.lessplain.ide.GraphLayerKey
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.BrowseState
import com.lessplain.model.UnderstandingRecord
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel

/** 工具窗口画布及其绑定独立释放；层级、返回入口和聚焦更新不重建图。 */
internal class PanelCanvasView(
    private val service: UnderstandingService,
    private val record: UnderstandingRecord,
    nodeId: String?,
    initialBrowse: BrowseState?,
    private val canvas: QuestionCanvas?,
    onQuestions: (UnderstandingRecord, String) -> Unit,
) : JPanel(BorderLayout()), AutoCloseable {
    private val graphKey = GraphLayerKey(record.id, nodeId)
    private val saved = if (canvas == null) service.graphStates.get(graphKey).browse ?: initialBrowse
        else graphBrowseState(service, graphKey)
    internal val diagram = createUnderstandingDiagram(canvas?.record ?: record, saved,
        clusters = canvas?.clusters.orEmpty(), examplePanels = canvas?.examplePanels.orEmpty()).apply {
        bindGraphState(service.graphStates, graphKey)
    }
    private val explorer = StaticExplorer((canvas?.record ?: record).graph, saved?.staticPath.orEmpty(),
        if (canvas == null) service.activeNodeId.takeIf { service.activeRecordId == record.id } ?: saved?.nodeId else saved?.nodeId,
        saved?.navigationState, scopes = canvas?.sources?.mapValues { it.value.record.id }.orEmpty())
    private val navigation = bindDiagramSourceNavigation(service, graphKey, diagram, explorer, record, canvas)
    private fun source(id: String) = canvas?.sources?.get(id) ?: if (canvas == null) SourceNode(record, id) else null
    private val copy = diagram.bindNodeContextCopy(service, ::source)
    private val content = diagram.bindContentActions(service, record.id, ::source,
        { canvas?.clusterSources?.get(it) }, titleRecord = record.takeIf { canvas == null },
        draftHost = CanvasDraftHost(graphKey, editor = false))
    private val questions = diagram.onQuestionBadgeClicked { id -> source(id)?.let { onQuestions(it.record, it.nodeId) } }
    private val back = JButton("‹ 返回")
    private val previous = JButton()
    private val controls = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(back)
        add(previous)
    }
    private val overlay = object : JLayeredPane() {
        override fun getPreferredSize() = diagram.jComponent.preferredSize
        override fun doLayout() {
            diagram.jComponent.setBounds(0, 0, width, height)
            val size = controls.preferredSize
            val gap = JBUI.scale(UnderstandingUiMetrics.GAP)
            controls.setBounds((width - size.width - gap).coerceAtLeast(0), gap, size.width, size.height)
        }
    }.apply {
        add(diagram.jComponent, JLayeredPane.DEFAULT_LAYER)
        add(controls, JLayeredPane.PALETTE_LAYER)
    }
    private val readOnly = JLabel("此项目已在另一 IDE 实例中打开，当前只读")
    private val fonts = CanvasUiFonts(listOf(back, previous, readOnly)) { revalidate() }
    private var focusedQuestion: String? = null
    private var navigate: (Int) -> Unit = {}
    private var returnPrevious: () -> Unit = {}
    private var parentIndex = -1
    private var closed = false

    init {
        border = JBUI.Borders.empty(UnderstandingUiMetrics.PAGE_PADDING)
        readOnly.isVisible = service.readOnly
        add(readOnly, BorderLayout.NORTH)
        add(overlay, BorderLayout.CENTER)
        back.addActionListener { if (parentIndex >= 0) navigate(parentIndex) }
        previous.addActionListener { returnPrevious() }
    }

    fun update(entries: List<GraphHierarchyEntry>, layers: List<FollowUpLayer>, focusQuestionId: String?,
        canReturn: Boolean, returnLabel: String, navigate: (Int) -> Unit, returnPrevious: () -> Unit) {
        this.navigate = navigate
        this.returnPrevious = returnPrevious
        diagram.setHierarchy(entries.mapIndexed { index, entry ->
            DiagramHierarchyItem(entry.title, entry.depth, index, entry.layers == layers)
        }, onNavigate = navigate)
        parentIndex = if (layers.isEmpty()) -1 else entries.indexOfFirst { it.layers == layers.dropLast(1) }
        back.isVisible = layers.isNotEmpty()
        back.isEnabled = parentIndex >= 0
        previous.text = returnLabel
        previous.isVisible = canReturn
        controls.isVisible = layers.isNotEmpty() || canReturn
        readOnly.isVisible = service.readOnly
        if (focusedQuestion != focusQuestionId) {
            focusedQuestion = focusQuestionId
            focusQuestionId?.let { questionId ->
                canvas?.clusters?.firstOrNull {
                    it.id == questionId || canvas.clusterSources[it.id]?.record?.questionId == questionId
                }?.id?.let(diagram::requestFocusCluster)
            }
        }
        overlay.revalidate()
        overlay.repaint()
    }

    override fun close() {
        if (closed) return
        closed = true
        navigation.close()
        questions.close()
        copy.close()
        content.close()
        fonts.close()
        Disposer.dispose(diagram)
        removeAll()
    }
}
