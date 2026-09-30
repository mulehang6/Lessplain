package com.master.understanding.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.master.understanding.core.UnderstandingCore
import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepResult
import com.master.understanding.diagram.UnderstandingVirtualFile
import com.master.understanding.model.UnderstandingContext
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.model.BrowseState
import com.master.understanding.ui.FollowUpLayer
import com.master.understanding.ui.graphHierarchyEntries
import com.master.understanding.storage.UnderstandingRepository
import com.master.understanding.storage.deleteRootRecord
import com.master.understanding.storage.pendingQuestions
import com.google.gson.Gson
import com.master.understanding.model.UNDERSTANDING_SCHEMA_VERSION
import java.awt.datatransfer.StringSelection
import java.awt.Component
import java.awt.Point
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.Timer

internal enum class PanelPage { WELCOME, HISTORY, CANVAS }

internal data class PanelLocation(
    val page: PanelPage,
    val recordId: String?,
    val nodeId: String?,
    val layers: List<FollowUpLayer>,
    val rootRecordId: String?,
    val focusQuestionId: String?,
)

class UnderstandingService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(UnderstandingService::class.java)
    val repository = UnderstandingRepository(Path.of(requireNotNull(project.basePath)))
    val core = UnderstandingCore(repository)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val selectionListeners = CopyOnWriteArrayList<(String, String?) -> Unit>()
    private val editorFiles = mutableMapOf<String, UnderstandingVirtualFile>()
    private val sourceHints = SourceInsightMarker(project, this)
    private val sourceScrollAnimation = SourceScrollAnimation()
    internal val graphStates = GraphViewStateStore(project)
    internal val resultInbox = ResultInbox(project)
    @Volatile private var pendingAutoOpenKey: String? = resultInbox.pending().lastOrNull()?.key
    internal var panelPage: PanelPage = PanelPage.WELCOME
    internal var panelFocusQuestionId: String? = null
    private var pageBeforeHistory = PanelPage.WELCOME
    private var previousLocation: PanelLocation? = null
    internal var selectedPendingQuestionId: String? = null
    private val contentActions = UnderstandingContentActions(this)
    private val resultTimer = Timer(180) { handlePendingResults() }.apply { isRepeats = false }
    private val focusListener = PropertyChangeListener {
        SwingUtilities.invokeLater { if (!project.isDisposed && panelIsActive()) showLatestPendingResult() }
    }

    init {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("focusOwner", focusListener)
    }

    /** Tool-window navigation survives Compose/Swing content recreation. */
    internal var panelLayers: List<FollowUpLayer> = emptyList()
    internal var panelRootRecordId: String? = null

    internal val canReturnToPrevious: Boolean get() = previousLocation != null
    internal val returnLabel: String get() = if (previousLocation?.page == PanelPage.CANVAS)
        "返回刚才的画布" else "返回刚才的页面"

    internal fun showWelcome() {
        panelPage = PanelPage.WELCOME
        previousLocation = null
        notifyChanged()
    }

    internal fun toggleHistory() {
        if (panelPage == PanelPage.HISTORY) panelPage = pageBeforeHistory
        else {
            pageBeforeHistory = panelPage
            panelPage = PanelPage.HISTORY
        }
        previousLocation = null
        notifyChanged()
    }

    internal fun showHistory() {
        if (panelPage == PanelPage.HISTORY) return
        pageBeforeHistory = panelPage
        panelPage = PanelPage.HISTORY
        previousLocation = null
        notifyChanged()
    }

    internal fun showRoot(record: UnderstandingRecord) {
        panelLayers = emptyList()
        panelRootRecordId = null
        panelFocusQuestionId = null
        previousLocation = null
        panelPage = PanelPage.CANVAS
        select(record, navigateSource = false)
        resultInbox.pending().filter { it.recordId == record.id }.forEach {
            resultInbox.markViewed(it.key)
            if (pendingAutoOpenKey == it.key) pendingAutoOpenKey = null
        }
        notifyChanged()
    }

    internal fun returnToPrevious() {
        val previous = previousLocation ?: return
        previousLocation = null
        panelPage = previous.page
        panelLayers = previous.layers
        panelRootRecordId = previous.rootRecordId
        panelFocusQuestionId = previous.focusQuestionId
        previous.recordId?.let { id ->
            runCatching { repository.getRecord(id) }.getOrNull()?.let {
                select(it, previous.nodeId, navigateSource = false)
            }
        }
        notifyChanged()
    }

    /** Called only after a tool has committed its result. Replayed request IDs keep the same result key. */
    fun resultSaved(
        recordId: String? = null,
        questionId: String? = null,
        answerId: String? = null,
        revision: Int? = null,
    ) {
        if (questionId == selectedPendingQuestionId) selectedPendingQuestionId = null
        val key = answerId?.let { "answer:$it" } ?: recordId?.let {
            if (revision != null && revision > 1) "record:$it:revision:$revision" else "record:$it"
        } ?: return
        val isUpdate = (revision != null && revision > 1) || questionId != null || answerId != null ||
            recordId?.let { repository.getRecord(it).parentId != null } == true
        if (!resultInbox.register(PendingResult(key, recordId, questionId, System.currentTimeMillis(),
                isUpdate = isUpdate))) return
        pendingAutoOpenKey = key
        notifyChanged()
        SwingUtilities.invokeLater { if (!project.isDisposed) resultTimer.restart() }
    }

    private fun handlePendingResults() {
        when (resultDelivery(panelIsVisible(), projectWindowIsActive())) {
            ResultDelivery.OPEN -> {
                showLatestPendingResult()
                return
            }
            ResultDelivery.WAIT -> return
            ResultDelivery.NOTIFY -> Unit
        }
        val fresh = resultInbox.pending().filterNot { it.notified }
        if (fresh.isEmpty()) return
        NotificationGroupManager.getInstance().getNotificationGroup("code.comprehension.results")
            .createNotification(resultNotificationTitle(fresh), "打开理解工具窗口即可查看", NotificationType.INFORMATION)
            .notify(project)
        resultInbox.markNotified(fresh.mapTo(mutableSetOf()) { it.key })
        notifyChanged()
    }

    private fun panelIsActive(): Boolean {
        return panelIsVisible() && projectWindowIsActive()
    }

    private fun panelIsVisible(): Boolean =
        ToolWindowManager.getInstance(project).getToolWindow("Understanding")?.isVisible == true

    private fun projectWindowIsActive(): Boolean = WindowManager.getInstance().getFrame(project)?.isActive == true

    internal fun showLatestPendingResult() {
        if (!panelIsActive()) return
        val key = pendingAutoOpenKey ?: return
        val result = resultInbox.pending().firstOrNull { it.key == key }
            ?: run { pendingAutoOpenKey = null; return }
        if (showResult(result, automatic = true)) pendingAutoOpenKey = null
    }

    internal fun showResult(result: PendingResult, automatic: Boolean = false): Boolean {
        val previous = if (automatic) previousLocation
            ?: PanelLocation(panelPage, activeRecordId, activeNodeId, panelLayers,
                panelRootRecordId, panelFocusQuestionId) else null
        val question = result.questionId?.let { runCatching { repository.getQuestion(it) }.getOrNull() }
        val context = question?.let { runCatching { repository.getContext(it.contextToken) }.getOrNull() }
        val ownerId = question?.ownerRecordId ?: context?.recordId
        val nodeId = context?.nodeId
        val targetRecord = result.recordId?.let { runCatching { repository.getRecord(it) }.getOrNull() }
        if (ownerId != null && nodeId != null) {
            val owner = runCatching { repository.getRecord(ownerId) }.getOrNull() ?: return false
            var root = owner
            val visited = mutableSetOf<String>()
            while (root.parentId != null && visited.add(root.id)) {
                root = runCatching { repository.getRecord(requireNotNull(root.parentId)) }.getOrNull() ?: break
            }
            val layer = FollowUpLayer(owner.id, nodeId)
            val entry = graphHierarchyEntries(repository, root.id).firstOrNull { it.layers.lastOrNull() == layer }
            panelRootRecordId = root.id
            panelLayers = entry?.layers ?: listOf(layer)
            panelFocusQuestionId = question.id
            panelPage = PanelPage.CANVAS
            select(owner, navigateSource = false)
        } else if (targetRecord != null) {
            panelLayers = emptyList()
            panelRootRecordId = null
            panelFocusQuestionId = null
            panelPage = PanelPage.CANVAS
            select(targetRecord, navigateSource = false)
        } else return false
        previousLocation = previous
        resultInbox.markViewed(result.key)
        if (pendingAutoOpenKey == result.key) pendingAutoOpenKey = null
        notifyChanged()
        return true
    }

    @Volatile
    var activeRecordId: String? = null
        private set

    @Volatile
    var activeNodeId: String? = null
        private set

    @Volatile
    var sourceLocationMessage: String? = null
        private set

    fun select(record: UnderstandingRecord, nodeId: String? = null, navigateSource: Boolean = true) {
        val recordChanged = activeRecordId != record.id
        val previousNodeId = activeNodeId
        val restored = nodeId ?: repository.getBrowseState(record.id)?.nodeId
        val validNode = restored?.takeIf { id -> record.graph.nodes.any { it.id == id } }
        activeRecordId = record.id
        activeNodeId = validNode
        sourceLocationMessage = null
        if (!repository.readOnly && nodeId != null) {
            val previous = repository.getBrowseState(record.id)
            repository.saveBrowseState((previous ?: BrowseState(record.id)).copy(nodeId = validNode))
        }
        if (recordChanged) notifyChanged()
        if (recordChanged || previousNodeId != validNode) {
            selectionListeners.forEach { listener ->
                if (SwingUtilities.isEventDispatchThread()) listener(record.id, validNode)
                else SwingUtilities.invokeLater { listener(record.id, validNode) }
            }
        }
        if (validNode != null) {
            val node = record.graph.nodes.first { it.id == validNode }
            val anchor = node.anchor ?: record.graph.evidence.firstOrNull { it.id in node.evidenceIds }?.anchor
            if (anchor == null) {
                sourceLocationMessage = "当前节点没有源码证据"
                SwingUtilities.invokeLater {
                    if (activeRecordId == record.id && activeNodeId == validNode) {
                        sourceScrollAnimation.cancel()
                        sourceHints.clear()
                    }
                }
                notifyChanged()
            } else {
                SwingUtilities.invokeLater {
                    if (project.isDisposed || activeRecordId != record.id || activeNodeId != validNode) return@invokeLater
                    sourceScrollAnimation.cancel()
                    runCatching {
                        val explorer = sourceExplorer(record, validNode)
                        val navigation = SourceInlayNavigation(
                            canPrevious = explorer.path.isNotEmpty(),
                            canNext = explorer.availableEdges().isNotEmpty(),
                            previous = { sourcePrevious(record.id, validNode) },
                            next = { component, point -> sourceNext(record.id, validNode, component, point) },
                        )
                        val located = sourceHints.show(anchor, node.label, navigation)
                        if (navigateSource && nodeId != null && located?.offset != null) {
                            navigateToSource(project, anchor, sourceScrollAnimation, located)
                            sourceHints.refreshOpenEditors()
                        }
                        located
                    }
                        .onSuccess { located ->
                            sourceLocationMessage = when (located?.location) {
                                LocationStatus.EXACT, LocationStatus.RELOCATED -> null
                                LocationStatus.AMBIGUOUS -> "源码定位失败：证据文本有多处匹配"
                                LocationStatus.UNRESOLVED -> "源码定位失败：当前文件中找不到对应证据"
                                null -> "源码定位失败：当前文件无法读取"
                            }
                        }
                        .onFailure { failure ->
                            log.warn("Unable to navigate to source for ${record.id}/$validNode", failure)
                            sourceLocationMessage = "源码定位失败：${failure.message ?: "未知错误"}"
                        }
                    if (sourceLocationMessage != null) notifyChanged()
                }
            }
        } else {
            SwingUtilities.invokeLater {
                if (activeNodeId == null) {
                    sourceScrollAnimation.cancel()
                    sourceHints.clear()
                }
            }
        }
    }

    private fun sourceExplorer(record: UnderstandingRecord, nodeId: String): StaticExplorer {
        val saved = graphStates.get(GraphLayerKey(record.id)).browse ?: repository.getBrowseState(record.id)
        return StaticExplorer(record.graph, saved?.staticPath.orEmpty(), nodeId)
    }

    private fun sourcePrevious(recordId: String, nodeId: String) {
        if (activeRecordId != recordId || activeNodeId != nodeId) return
        val record = repository.getRecord(recordId)
        val explorer = sourceExplorer(record, nodeId)
        if (explorer.back()) commitSourceStep(record, explorer)
    }

    private fun sourceNext(recordId: String, nodeId: String, component: Component, point: Point) {
        if (activeRecordId != recordId || activeNodeId != nodeId) return
        val record = repository.getRecord(recordId)
        val explorer = sourceExplorer(record, nodeId)
        when (val result = explorer.next()) {
            is StepResult.Moved -> commitSourceStep(record, explorer)
            is StepResult.Choose -> JPopupMenu().apply {
                result.edges.forEach { edge ->
                    val target = record.graph.nodes.firstOrNull { it.id == edge.to }?.label ?: edge.to
                    add(JMenuItem(edge.condition?.let { "$it → $target" } ?: target).apply {
                        addActionListener {
                            if (activeRecordId != recordId || activeNodeId != nodeId) return@addActionListener
                            val current = sourceExplorer(record, nodeId)
                            if (current.choose(edge.id) is StepResult.Moved) commitSourceStep(record, current)
                        }
                    })
                }
                show(component, point.x, point.y + 2)
            }
            else -> Unit
        }
    }

    private fun commitSourceStep(record: UnderstandingRecord, explorer: StaticExplorer) {
        val nextNodeId = explorer.currentNodeId
        val previous = repository.getBrowseState(record.id)
        graphStates.update(GraphLayerKey(record.id)) { state ->
            val browse = state.browse ?: previous ?: BrowseState(record.id)
            state.copy(browse = browse.copy(nodeId = nextNodeId, staticPath = explorer.path))
        }
        if (!repository.readOnly) {
            repository.saveBrowseState((previous ?: BrowseState(record.id)).copy(
                nodeId = nextNodeId, staticPath = explorer.path,
            ))
        }
        select(record, nextNodeId)
    }

    fun captureCurrentContext(): UnderstandingContext {
        val record = activeRecordId?.let { repository.getRecord(it) }
        val node = record?.graph?.nodes?.firstOrNull { it.id == activeNodeId }
        val source = node?.anchor ?: captureEditorAnchor(project)
        return core.captureContext(record?.id, node?.id, source, repository.getBrowseState(record?.id ?: "")?.staticPath.orEmpty())
    }

    fun selectedPendingQuestion() = repository.pendingQuestions().let { pending ->
        pending.firstOrNull { it.id == selectedPendingQuestionId } ?: pending.singleOrNull()
    }

    fun addNodeNote(record: UnderstandingRecord, nodeId: String) = contentActions.addNodeNote(record, nodeId)
    fun showNodeNotes(record: UnderstandingRecord, nodeId: String, component: Component, point: Point) =
        contentActions.showNodeNotes(record, nodeId, component, point)
    fun editRecordTitle(recordId: String) = contentActions.editRecordTitle(recordId)

    internal fun refreshOpenDiagrams() {
        val manager = FileEditorManager.getInstance(project)
        val opened = editorFiles.values.filter(manager::isFileOpen)
        if (opened.isEmpty()) return
        val selected = manager.selectedFiles.firstOrNull() as? UnderstandingVirtualFile
        opened.forEach(manager::closeFile)
        editorFiles.clear()
        val replacements = opened.associateWith { old ->
            val current = runCatching { repository.getRecord(old.recordId) }.getOrNull() ?: return@associateWith old
            editorFile(current, old.questionNodeId)
        }
        replacements.forEach { (old, replacement) ->
            replacement.parentFile = old.parentFile?.let(replacements::get)
        }
        opened.filter { it != selected }.forEach { manager.openFile(replacements.getValue(it), false) }
        selected?.takeIf { it in replacements }?.let { manager.openFile(replacements.getValue(it), true) }
    }

    fun deleteRoot(recordId: String): Boolean {
        if (repository.readOnly) return false
        if (JOptionPane.showConfirmDialog(null, "永久删除这条理解图及其追问？", "删除历史记录",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return false
        val deleted = repository.deleteRootRecord(recordId)
        resultInbox.removeDeleted(deleted.recordIds, deleted.questionIds)
        editorFiles.entries.removeIf { (_, file) ->
            if (file.recordId !in deleted.recordIds) false else {
                FileEditorManager.getInstance(project).closeFile(file)
                true
            }
        }
        if (activeRecordId in deleted.recordIds) {
            activeRecordId = null
            activeNodeId = null
            panelLayers = emptyList()
            panelRootRecordId = null
            panelFocusQuestionId = null
            panelPage = PanelPage.HISTORY
            sourceHints.clear()
        }
        if (selectedPendingQuestionId in deleted.questionIds) selectedPendingQuestionId = null
        previousLocation = null
        notifyChanged()
        return true
    }

    /** Copy a fixed source token for a node, without creating an empty question. */
    fun copyNodeContext(record: UnderstandingRecord, nodeId: String) {
        val node = record.graph.nodes.firstOrNull { it.id == nodeId }
            ?: throw IllegalArgumentException("节点不属于理解记录")
        val anchor = node.anchor ?: record.graph.evidence.firstOrNull { it.id in node.evidenceIds }?.anchor
        val context = core.captureContext(record.id, nodeId, anchor,
            repository.getBrowseState(record.id)?.staticPath.orEmpty())
        val payload = Gson().toJson(mapOf(
            "protocolVersion" to UNDERSTANDING_SCHEMA_VERSION,
            "contextToken" to context.token,
            "recordId" to record.id,
            "nodeId" to nodeId,
            "nodeLabel" to node.label,
        ))
        CopyPasteManager.getInstance().setContents(StringSelection(payload))
    }

    fun onChanged(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun onSelectionChanged(listener: (String, String?) -> Unit): AutoCloseable {
        selectionListeners += listener
        return AutoCloseable { selectionListeners -= listener }
    }

    fun editorFile(record: UnderstandingRecord, questionNodeId: String? = null): UnderstandingVirtualFile =
        editorFiles.getOrPut("${record.id}:${record.revision}:${questionNodeId.orEmpty()}") {
            UnderstandingVirtualFile(record, questionNodeId)
        }

    fun notifyChanged() {
        listeners.forEach { listener -> SwingUtilities.invokeLater { listener() } }
    }

    override fun dispose() {
        resultTimer.stop()
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removePropertyChangeListener("focusOwner", focusListener)
        sourceScrollAnimation.dispose()
        sourceHints.dispose()
        listeners.clear()
        selectionListeners.clear()
        repository.close()
    }
}
