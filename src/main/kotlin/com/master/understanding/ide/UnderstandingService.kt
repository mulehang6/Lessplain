package com.master.understanding.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.master.understanding.core.UnderstandingCore
import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepResult
import com.master.understanding.diagram.UnderstandingVirtualFile
import com.master.understanding.model.UnderstandingContext
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.model.BrowseState
import com.master.understanding.ui.FollowUpLayer
import com.master.understanding.storage.UnderstandingRepository
import com.google.gson.Gson
import com.master.understanding.model.UNDERSTANDING_SCHEMA_VERSION
import java.awt.datatransfer.StringSelection
import java.awt.Component
import java.awt.Point
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

class UnderstandingService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(UnderstandingService::class.java)
    val repository = UnderstandingRepository(Path.of(requireNotNull(project.basePath)))
    val core = UnderstandingCore(repository)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val selectionListeners = CopyOnWriteArrayList<(String, String?) -> Unit>()
    private val editorFiles = mutableMapOf<String, UnderstandingVirtualFile>()
    private val sourceHints = SourceInsightMarker(project, this)
    internal val graphStates = GraphViewStateStore(project)

    /** Tool-window navigation survives Compose/Swing content recreation. */
    internal var panelLayers: List<FollowUpLayer> = emptyList()
    internal var panelRootRecordId: String? = null

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
                    if (activeRecordId == record.id && activeNodeId == validNode) sourceHints.clear()
                }
                notifyChanged()
            } else {
                SwingUtilities.invokeLater {
                    if (project.isDisposed || activeRecordId != record.id || activeNodeId != validNode) return@invokeLater
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
                            navigateToSource(project, anchor, located)
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
            SwingUtilities.invokeLater { if (activeNodeId == null) sourceHints.clear() }
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
        sourceHints.dispose()
        listeners.clear()
        selectionListeners.clear()
        repository.close()
    }
}
