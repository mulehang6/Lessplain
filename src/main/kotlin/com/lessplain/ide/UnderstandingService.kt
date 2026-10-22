package com.lessplain.ide

import com.google.gson.Gson
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.lessplain.core.BackgroundTasks
import com.lessplain.core.StaticExplorer
import com.lessplain.core.UnderstandingCore
import com.lessplain.diagram.LoadingUnderstandingFileEditor
import com.lessplain.diagram.UnderstandingVirtualFile
import com.lessplain.model.UnderstandingContext
import com.lessplain.model.UnderstandingRecord
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.deleteRootRecord
import com.lessplain.ui.FollowUpLayer
import kotlinx.coroutines.CoroutineScope
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
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

class UnderstandingService(private val project: Project, coroutineScope: CoroutineScope) : Disposable {
    private val log = Logger.getInstance(UnderstandingService::class.java)
    @Volatile
    private var disposed = false
    private var storageError: String? = null
    private val tasks = BackgroundTasks(coroutineScope) { action -> SwingUtilities.invokeLater(action) }
    internal val storage = UnderstandingStorage(
        Path.of(requireNotNull(project.basePath)), tasks,
        ::notifyChanged, ::storageFailed
    )
    val repository: UnderstandingRepository get() = storage.repository
    val core: UnderstandingCore by lazy {
        UnderstandingCore(
            repository,
            prepareGraph = { prepareGraphAnchors(project, it) })
    }
    val readOnly: Boolean get() = storage.readOnly
    internal fun <T> readBackground(read: () -> T, completed: (Result<T>) -> Unit): AutoCloseable =
        tasks.dispatch(read, completed)

    internal fun isCurrent(record: UnderstandingRecord): Boolean =
        storage.record(record.id)?.revision == record.revision

    internal fun storageFailed(failure: Throwable) {
        log.warn("Lessplain storage operation failed", failure)
        storageError = "数据操作失败：${failure.message ?: "未知错误"}"
        notifyChanged()
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val selectionListeners = CopyOnWriteArrayList<(String, String?) -> Unit>()
    private val editorFiles = mutableMapOf<String, UnderstandingVirtualFile>()
    internal val graphStates = GraphViewStateStore(project)
    private val selection = UnderstandingSelection(project, this, storage)
    internal val resultInbox = ResultInbox(project)
    @Volatile
    private var pendingAutoOpenKey: String? = resultInbox.pending().lastOrNull()?.key
    internal var panelPage: PanelPage = PanelPage.WELCOME
    internal var panelFocusQuestionId: String? = null
    private var previousLocation: PanelLocation? = null
    private var navigationGeneration = 0L
    internal val contentActions = UnderstandingContentActions(this, project)
    private val deletionConfirmation = RootDeletionConfirmation(project, ::deleteConfirmedRoot)
    private val resultTimer = Timer(180) { handlePendingResults() }.apply { isRepeats = false }
    private val focusListener = PropertyChangeListener {
        SwingUtilities.invokeLater { if (!project.isDisposed && panelIsActive()) showLatestPendingResult() }
    }

    init {
        storage.initialize()
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("focusOwner", focusListener)
    }

    /** 工具窗口导航状态跨 Compose 和 Swing 内容重建保留。 */
    internal var panelLayers: List<FollowUpLayer> = emptyList()
    internal var panelRootRecordId: String? = null

    internal val canReturnToPrevious: Boolean get() = previousLocation != null
    internal val returnLabel: String
        get() = if (previousLocation?.page == PanelPage.CANVAS)
            "返回刚才的画布" else "返回刚才的页面"

    internal fun showWelcome() {
        navigationGeneration++
        panelPage = PanelPage.WELCOME
        previousLocation = null
        notifyChanged()
    }

    internal fun showHistory() {
        navigationGeneration++
        if (panelPage == PanelPage.HISTORY) return
        panelPage = PanelPage.HISTORY
        previousLocation = null
        notifyChanged()
    }

    internal fun showRoot(record: UnderstandingRecord) {
        navigationGeneration++
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
        navigationGeneration++
        val previous = previousLocation ?: return
        previousLocation = null
        panelPage = previous.page
        panelLayers = previous.layers
        panelRootRecordId = previous.rootRecordId
        panelFocusQuestionId = previous.focusQuestionId
        previous.recordId?.let { id ->
            storage.record(id)?.let {
                select(it, previous.nodeId, navigateSource = false)
            }
        }
        notifyChanged()
    }

    /** 仅在工具提交结果后调用，重放请求 ID 保持同一结果键。 */
    fun resultSaved(
        recordId: String? = null,
        questionId: String? = null,
        answerId: String? = null,
        revision: Int? = null,
    ) {
        val key = answerId?.let { "answer:$it" } ?: recordId?.let {
            if (revision != null && revision > 1) "record:$it:revision:$revision" else "record:$it"
        } ?: return
        recordId?.let(storage::loadRecord)
        if (recordId != null && revision != null && revision > 1) {
            SwingUtilities.invokeLater { if (!disposed) refreshOpenDiagrams(recordId) }
        }
        val isUpdate = (revision != null && revision > 1) || questionId != null || answerId != null ||
                recordId?.let { storage.record(it)?.parentId != null } == true
        if (!resultInbox.register(
                PendingResult(
                    key, recordId, questionId, System.currentTimeMillis(),
                    isUpdate = isUpdate
                )
            )
        ) return
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
            .createNotification(
                resultNotificationTitle(fresh),
                "打开理解工具窗口即可查看",
                NotificationType.INFORMATION
            )
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
            ?: PanelLocation(
                panelPage, activeRecordId, activeNodeId, panelLayers,
                panelRootRecordId, panelFocusQuestionId
            ) else null
        val request = ++navigationGeneration
        readBackground({ loadResultLocation(repository, result)?.also { storage.remember(it.record) } }) { loaded ->
            if (request != navigationGeneration) return@readBackground
            loaded.onFailure(::storageFailed).onSuccess { destination ->
                if (destination == null) return@onSuccess
                panelLayers = destination.layers
                panelRootRecordId = destination.rootRecordId
                panelFocusQuestionId = destination.questionId
                panelPage = PanelPage.CANVAS
                select(destination.record, navigateSource = false)
                previousLocation = previous
                resultInbox.markViewed(result.key)
                if (pendingAutoOpenKey == result.key) pendingAutoOpenKey = null
                notifyChanged()
            }
        }
        return true
    }

    val activeRecordId: String? get() = selection.activeRecordId
    val activeNodeId: String? get() = selection.activeNodeId
    val sourceLocationMessage: String? get() = selection.sourceLocationMessage ?: storageError

    fun select(
        record: UnderstandingRecord,
        nodeId: String? = null,
        navigateSource: Boolean = true,
        staticPath: List<String>? = null,
        navigationState: com.lessplain.model.RouteNavigationState? = null,
    ) {
        navigationGeneration++
        selection.select(record, nodeId, navigateSource, staticPath, navigationState)
    }

    internal fun selectionChanged(recordId: String, nodeId: String?) {
        selectionListeners.forEach { listener ->
            if (SwingUtilities.isEventDispatchThread()) listener(recordId, nodeId)
            else SwingUtilities.invokeLater {
                if (!disposed && listener in selectionListeners) listener(
                    recordId,
                    nodeId
                )
            }
        }
    }

    internal fun logSourceFailure(recordId: String, nodeId: String, failure: Throwable) {
        log.warn("Unable to navigate to source for $recordId/$nodeId", failure)
    }

    fun captureCurrentContext(): UnderstandingContext {
        val record = activeRecordId?.let { repository.getRecord(it) }
        val node = record?.graph?.nodes?.firstOrNull { it.id == activeNodeId }
        val source = node?.anchor ?: captureEditorAnchor(project)
        val staticPath = record?.let {
            val browse = graphStates.get(GraphLayerKey(it.id)).browse ?: repository.getBrowseState(it.id)
            StaticExplorer(it.graph, browse?.staticPath.orEmpty(), browse?.nodeId, browse?.navigationState)
                .apply { node?.id?.let(::synchronizeNode) }.path
        }.orEmpty()
        return core.captureContext(record?.id, node?.id, source, staticPath)
    }

    fun addNodeNote(record: UnderstandingRecord, nodeId: String) = contentActions.addNodeNote(record, nodeId)
    fun showNodeNotes(record: UnderstandingRecord, nodeId: String, component: Component, point: Point) =
        contentActions.showNodeNotes(record, nodeId, component, point)

    fun editRecordTitle(recordId: String) = contentActions.editRecordTitle(recordId)

    internal fun refreshOpenDiagrams(recordId: String) = uiRead {
        val manager = FileEditorManager.getInstance(project)
        storage.record(recordId)?.let { current ->
            editorFiles.values.filter { it.recordId == recordId }.forEach {
                it.update(current)
                manager.updateFilePresentation(it)
            }
        }
        manager.openFiles.filterIsInstance<UnderstandingVirtualFile>().forEach { file ->
            manager.getAllEditors(file).filterIsInstance<LoadingUnderstandingFileEditor>().forEach { it.refreshRecord(recordId) }
        }
    }

    fun deleteRoot(recordId: String) {
        if (!readOnly) storage.record(recordId)?.let(deletionConfirmation::show)
    }

    private fun deleteConfirmedRoot(recordId: String) {
        check(!project.isDisposed && !readOnly) { "当前项目不可删除记录" }
        readBackground({ repository.deleteRootRecord(recordId) }) { result ->
            result.onFailure(::storageFailed).onSuccess { deleted ->
                storage.remove(deleted.recordIds)
                resultInbox.removeDeleted(deleted.recordIds, deleted.questionIds)
                editorFiles.entries.removeIf { (_, file) ->
                    if (file.recordId !in deleted.recordIds) false else {
                        FileEditorManager.getInstance(project).closeFile(file)
                        true
                    }
                }
                if (activeRecordId in deleted.recordIds) {
                    selection.reset()
                    panelLayers = emptyList()
                    panelRootRecordId = null
                    panelFocusQuestionId = null
                    panelPage = PanelPage.HISTORY
                    selection.clear()
                }
                previousLocation = null
                notifyChanged()
            }
        }
    }

    /** 复制节点的固定来源 token，不创建空问题。 */
    fun copyNodeContext(record: UnderstandingRecord, nodeId: String, copied: () -> Unit = {}): AutoCloseable {
        val node = record.graph.nodes.firstOrNull { it.id == nodeId }
            ?: throw IllegalArgumentException("节点不属于理解记录")
        val anchor = node.anchor ?: record.graph.evidence.firstOrNull { it.id in node.evidenceIds }?.anchor
        val browse = graphStates.get(GraphLayerKey(record.id)).browse ?: storage.browse(record.id)
        val route = StaticExplorer(record.graph, browse?.staticPath.orEmpty(), browse?.nodeId, browse?.navigationState)
        route.synchronizeNode(nodeId)
        val path = route.path.toList()
        return readBackground({ core.captureContext(record.id, nodeId, anchor, path) }) { result ->
            result.onFailure(::storageFailed).onSuccess { context ->
                val payload = Gson().toJson(
                    mapOf(
                        "contextId" to context.token,
                        "recordId" to record.id,
                        "nodeId" to nodeId,
                        "nodeLabel" to node.label,
                    )
                )
                CopyPasteManager.getInstance().setContents(StringSelection(payload))
                copied()
            }
        }
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
        editorFiles.getOrPut("${record.id}:${questionNodeId.orEmpty()}") {
            UnderstandingVirtualFile(record, questionNodeId)
        }.also { it.update(record) }

    fun notifyChanged() {
        if (disposed) return
        listeners.forEach { listener ->
            SwingUtilities.invokeLater {
                if (!disposed && listener in listeners) listener()
            }
        }
    }

    override fun dispose() {
        disposed = true
        selection.close()
        tasks.close()
        deletionConfirmation.close()
        contentActions.close()
        resultTimer.stop()
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removePropertyChangeListener("focusOwner", focusListener)
        listeners.clear()
        selectionListeners.clear()
        storage.close()
    }
}
