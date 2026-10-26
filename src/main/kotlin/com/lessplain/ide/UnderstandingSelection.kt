package com.lessplain.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.lessplain.core.StaticExplorer
import com.lessplain.core.cancellationAwareResult
import com.lessplain.model.BrowseState
import com.lessplain.model.UnderstandingRecord
import javax.swing.SwingUtilities

/** 活动选择、后台定位和源码提示共享一个生命周期，Service 只负责装配和跨视图入口。 */
internal class UnderstandingSelection(
    private val project: Project,
    private val service: UnderstandingService,
    private val storage: UnderstandingStorage,
) : AutoCloseable {
    private var disposed = false
    private val sourceSelection = SourceSelectionController(project, service)
    private var selectionGeneration = 0L
    private var selectionRead: AutoCloseable? = null
    private val sourceHints = SourceInsightMarker(project, service)
    private val sourceScrollAnimation = SourceScrollAnimation()
    private val graphStates = service.graphStates
    private val sourceNavigation = SourceGraphNavigation(
        repository = SourceNavigationData(
            { id -> requireNotNull(storage.record(id)) { "记录快照不存在" } },
            storage::browse, storage::saveBrowse, { service.readOnly },
        ),
        graphStates = graphStates,
        isSelected = { recordId, nodeId -> activeRecordId == recordId && activeNodeId == nodeId },
        selectNode = { record, nodeId -> select(record, nodeId) },
        showGraph = { record, ready ->
            service.showRoot(record)
            ToolWindowManager.getInstance(project).getToolWindow("Understanding")?.show {
                if (!project.isDisposed) ready()
            }
        },
    )

    @Volatile
    var activeRecordId: String? = null
        private set

    @Volatile
    var activeNodeId: String? = null
        private set

    private var activeRecordRevision: Int? = null
    private var activeStaticPath: List<String>? = null

    @Volatile
    var sourceLocationMessage: String? = null
        private set

    fun select(
        record: UnderstandingRecord,
        nodeId: String? = null,
        navigateSource: Boolean = true,
        staticPath: List<String>? = null,
        navigationState: com.lessplain.model.RouteNavigationState? = null,
        activateSource: Boolean = false,
    ) {
        if (disposed || project.isDisposed) return
        val request = ++selectionGeneration
        selectionRead?.close()
        selectionRead = null
        storage.remember(record)
        val current = storage.record(record.id) ?: return
        if (current.revision != record.revision) {
            select(current, nodeId, navigateSource, staticPath, navigationState, activateSource)
            return
        }
        if (storage.browse(record.id) == null) {
            sourceSelection.cancel()
            sourceScrollAnimation.cancel()
            sourceHints.clear()
            selectionRead = service.readBackground({ storage.loadBrowse(record.id) }) { result ->
                if (request == selectionGeneration) result.onSuccess { browse ->
                    storage.rememberBrowse(record.id, browse)
                    applySelection(record, nodeId, navigateSource, staticPath, navigationState, activateSource)
                }.onFailure(service::storageFailed)
            }
        } else applySelection(record, nodeId, navigateSource, staticPath, navigationState, activateSource)
    }

    private fun applySelection(
        record: UnderstandingRecord,
        nodeId: String?,
        navigateSource: Boolean,
        staticPath: List<String>?,
        navigationState: com.lessplain.model.RouteNavigationState?,
        activateSource: Boolean,
    ) {
        val recordChanged = activeRecordId != record.id
        val previousNodeId = activeNodeId
        val previousRevision = activeRecordRevision
        val previousStaticPath = activeStaticPath
        val key = GraphLayerKey(record.id)
        val graphBrowse = graphStates.get(key).browse
        val repositoryBrowse = storage.browse(record.id)
        val previousBrowse = graphBrowse?.takeIf { it.nodeId != null || it.staticPath.isNotEmpty() }
            ?: repositoryBrowse ?: graphBrowse
        val restored = nodeId ?: graphBrowse?.nodeId ?: repositoryBrowse?.nodeId
        val validNode = restored?.takeIf { id -> record.graph.nodes.any { it.id == id } }
        val explorer = if (staticPath != null && validNode != null) {
            StaticExplorer(record.graph, staticPath, validNode, navigationState)
        } else {
            StaticExplorer(
                record.graph, previousBrowse?.staticPath.orEmpty(), previousBrowse?.nodeId,
                previousBrowse?.navigationState
            )
        }
        if (validNode != null) {
            if (staticPath == null) explorer.synchronizeNode(validNode)
        } else if (nodeId != null || previousBrowse?.nodeId != null) {
            explorer.restorePath(emptyList(), null)
        }
        val selectedPath = explorer.path
        val selectedNavigation = explorer.navigationState
        val browseChanged =
            previousBrowse?.nodeId != validNode || previousBrowse?.staticPath.orEmpty() != selectedPath ||
                    previousBrowse?.navigationState != selectedNavigation
        val selectionChanged = recordChanged || previousNodeId != validNode || previousRevision != record.revision ||
                (activeRecordId == record.id && previousStaticPath != selectedPath)

        if (nodeId != null || browseChanged) {
            graphStates.update(key) { state ->
                val browse = state.browse ?: repositoryBrowse ?: BrowseState(record.id)
                state.copy(
                    browse = browse.copy(
                        nodeId = validNode,
                        staticPath = selectedPath,
                        navigationState = selectedNavigation
                    )
                )
            }
        }
        if (!service.readOnly && (nodeId != null || repositoryBrowse?.nodeId != validNode ||
                    repositoryBrowse?.staticPath.orEmpty() != selectedPath || repositoryBrowse?.navigationState != selectedNavigation)
        ) {
            storage.saveBrowse(
                (repositoryBrowse ?: BrowseState(record.id)).copy(
                    nodeId = validNode,
                    staticPath = selectedPath,
                    navigationState = selectedNavigation,
                )
            )
        }

        val selectionUnchanged = !selectionChanged
        activeRecordId = record.id
        activeNodeId = validNode
        activeRecordRevision = record.revision
        activeStaticPath = selectedPath
        if (!selectionUnchanged) sourceLocationMessage = null
        if (recordChanged || previousStaticPath != selectedPath) service.notifyChanged()
        if (selectionChanged) {
            sourceSelection.cancel()
            sourceScrollAnimation.cancel()
            sourceHints.clear()
            sourceNavigation.cancelChoices()
            service.selectionChanged(record.id, validNode)
        }
        if (selectionUnchanged && sourceLocationMessage == null && !(activateSource && navigateSource)) return
        if (validNode != null) {
            val node = record.graph.nodes.first { it.id == validNode }
            val anchor = node.anchor ?: record.graph.evidence.firstOrNull { it.id in node.evidenceIds }?.anchor
            if (anchor == null) {
                sourceLocationMessage = "当前节点没有源码证据"
                SwingUtilities.invokeLater {
                    if (activeRecordId == record.id && activeNodeId == validNode && activeRecordRevision == record.revision) {
                        sourceScrollAnimation.cancel()
                        sourceHints.clear()
                    }
                }
                service.notifyChanged()
            } else {
                sourceSelection.request(anchor, valid = {
                    !project.isDisposed && activeRecordId == record.id && activeNodeId == validNode &&
                            activeRecordRevision == record.revision
                }) { result ->
                    val applied = result.fold(onSuccess = { resolution -> cancellationAwareResult {
                        val explorer = sourceNavigation.explorer(record, validNode)
                        val navigation = SourceInlayNavigation(
                            canPrevious = explorer.canPrevious,
                            canNext = explorer.canNext,
                            previous = { sourceNavigation.previous(record.id, validNode) },
                            next = { sourceNavigation.next(record.id, validNode) },
                            cancelChoices = { sourceNavigation.cancelChoices(record.id, validNode) },
                            evidenceChanged = {
                                if (activeRecordId == record.id && activeNodeId == validNode && activeRecordRevision == record.revision) {
                                    sourceHints.clear()
                                    sourceLocationMessage = "原片段已变化"
                                    service.notifyChanged()
                                }
                            },
                        )
                        val located = sourceHints.show(
                            anchor, resolution, node.label, navigation,
                            SourceNodeBinding(record.id, record.revision, validNode, node.label, selectedPath.toList())
                        )
                        if (navigateSource && nodeId != null && located?.offset != null) {
                            navigateToSource(project, anchor, sourceScrollAnimation, located, resolution.file)
                            sourceHints.refreshOpenEditors()
                        }
                        sourceEvidenceMessage(located)
                    } }, onFailure = { Result.failure(it) })
                    applied.onSuccess { sourceLocationMessage = it }
                        .onFailure { failure ->
                            service.logSourceFailure(record.id, validNode, failure)
                            sourceLocationMessage = "源码定位失败：${failure.message ?: "未知错误"}"
                        }
                    if (sourceLocationMessage != null) service.notifyChanged()
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

    fun clear() {
        sourceSelection.cancel(); sourceScrollAnimation.cancel(); sourceHints.clear()
    }

    fun reset() {
        selectionGeneration++
        selectionRead?.close()
        activeRecordId = null
        activeNodeId = null
        activeRecordRevision = null
        activeStaticPath = null
        sourceLocationMessage = null
        sourceNavigation.cancelChoices()
        clear()
    }

    override fun close() {
        disposed = true
        selectionRead?.close()
        sourceSelection.close()
        sourceNavigation.close()
        sourceScrollAnimation.dispose()
        sourceHints.dispose()
    }
}
