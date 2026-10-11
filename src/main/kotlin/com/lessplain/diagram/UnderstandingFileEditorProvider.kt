package com.lessplain.diagram

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ide.setToolTipText
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.GraphLayerKey
import com.lessplain.model.UnderstandingRecord
import com.lessplain.core.StaticExplorer
import com.lessplain.ui.buildQuestionCanvas
import com.lessplain.ui.FollowUpLayer
import com.lessplain.ui.graphHierarchyEntries
import com.lessplain.ui.questionsForNode
import com.lessplain.ui.bindContentActions
import com.lessplain.ui.SourceNode
import com.lessplain.ui.GraphHierarchyEntry
import com.lessplain.ui.dispatchBackgroundRead
import com.lessplain.core.LatestBackgroundRead
import com.intellij.util.ui.JBUI
import com.lessplain.ui.CanvasUiFonts
import com.lessplain.ui.UnderstandingUiMetrics
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel

/** 编辑器标签页和工具窗口分别绘制同一个不可变记录版本。 */
class UnderstandingVirtualFile(record: UnderstandingRecord, val questionNodeId: String? = null) :
    LightVirtualFile(understandingTabTitle(record, questionNodeId), PlainTextFileType.INSTANCE, "") {
    val recordId: String = record.id
    val revision: Int = record.revision
    val fullTitle: String = if (questionNodeId == null) record.displayTitle ?: record.question else {
        val node = record.graph.nodes.firstOrNull { it.id == questionNodeId }?.label ?: "节点"
        "${record.displayTitle ?: record.question} / $node 的追问"
    }
    var parentFile: UnderstandingVirtualFile? = null
}

internal fun understandingTabTitle(record: UnderstandingRecord, questionNodeId: String?): String {
    val title = (questionNodeId?.let { id -> record.graph.nodes.firstOrNull { it.id == id }?.label }
        ?: record.displayTitle ?: record.question).replace(Regex("\\s+"), " ").trim()
    val limit = 12
    val count = title.codePointCount(0, title.length)
    val short = if (count > limit) title.substring(0, title.offsetByCodePoints(0, limit)) + "…" else title
    return short
}

class UnderstandingFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is UnderstandingVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        UnderstandingFileEditor(project, file as UnderstandingVirtualFile)

    override fun getEditorTypeId(): String = "lessplain-diagram"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class UnderstandingFileEditor(private val project: Project, private val virtualFile: UnderstandingVirtualFile) :
    UserDataHolderBase(), FileEditor {
    private val changes = PropertyChangeSupport(this)
    private val service = project.getService(UnderstandingService::class.java)
    private val record = service.repository.getRecord(virtualFile.recordId)
    private val graphKey = GraphLayerKey(record.id, virtualFile.questionNodeId)
    private val questionCanvas = virtualFile.questionNodeId?.let { nodeId ->
        buildQuestionCanvas(service, record, nodeId, questionsForNode(service, record, nodeId), record.revision)
    }
    private val builder = (questionCanvas?.let { canvas ->
        createUnderstandingDiagram(canvas.record, graphBrowseState(service, graphKey),
            clusters = canvas.clusters, examplePanels = canvas.examplePanels)
    } ?: createUnderstandingDiagram(record, graphBrowseState(service, graphKey))).apply {
        bindGraphState(service.graphStates, graphKey, NavigationStateScope.EDITOR_LOCAL)
    }
    private val explorer = (questionCanvas?.record ?: record).graph.nodes.firstOrNull()?.let {
        val saved = graphBrowseState(service, graphKey)
        StaticExplorer((questionCanvas?.record ?: record).graph, saved?.staticPath.orEmpty(), saved?.nodeId,
            saved?.navigationState, scopes = questionCanvas?.sources?.mapValues { it.value.record.id }.orEmpty())
    }
    private val navigation = bindDiagramSourceNavigation(service, graphKey, builder, explorer, record, questionCanvas)
    private val questionSelection = builder.onQuestionBadgeClicked { nodeId ->
        val source = questionCanvas?.sources?.get(nodeId)
            ?: if (questionCanvas == null) SourceNode(record, nodeId) else null
        source?.takeIf { questionsForNode(service, it.record, it.nodeId).isNotEmpty() }?.let {
            service.select(it.record, it.nodeId, navigateSource = false)
            openRecordInEditor(project, it.record, it.nodeId, replace = virtualFile)
        }
    }
    private val copySelection = builder.onCopyNodeContext { nodeId ->
        val source = questionCanvas?.sources?.get(nodeId)
        if (source != null) service.copyNodeContext(source.record, source.nodeId)
        else if (questionCanvas == null) service.copyNodeContext(record, nodeId)
        builder.showCopied(nodeId)
    }
    private val content = builder.bindContentActions(service, record.id, { nodeId ->
        questionCanvas?.sources?.get(nodeId) ?: if (questionCanvas == null) SourceNode(record, nodeId) else null
    }, { id -> questionCanvas?.clusterSources?.get(id) }, titleRecord = record.takeIf { questionCanvas == null }, onStaleSource = navigation::close)

    private val backButton = JButton("‹ 返回").apply {
        setToolTipText(HtmlChunk.text("返回上一级理解图"))
        addActionListener {
            virtualFile.parentFile?.let { parent ->
                openExistingEditor(project, parent)
                FileEditorManager.getInstance(project).closeFile(virtualFile)
            }
        }
    }
    private val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(UnderstandingUiMetrics.GAP), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(UnderstandingUiMetrics.SMALL_GAP, UnderstandingUiMetrics.SMALL_GAP)
        add(backButton)
    }
    private val panel = JPanel(BorderLayout()).apply {
        add(builder.jComponent, BorderLayout.CENTER)
    }
    private val fonts = CanvasUiFonts(listOf(backButton), onRefreshed = { panel.revalidate() })
    private var disposed = false
    private val hierarchyLoader: LatestBackgroundRead<List<GraphHierarchyEntry>>
    private val hierarchyChanged: AutoCloseable

    init {
        val lineage = generateSequence(virtualFile) { it.parentFile }.toList().asReversed()
        val rootRecordId = lineage.first().recordId
        val currentLayers = lineage.drop(1).mapNotNull { file ->
            file.questionNodeId?.let { FollowUpLayer(file.recordId, it) }
        }
        hierarchyLoader = LatestBackgroundRead(::dispatchBackgroundRead) { loaded ->
            loaded.onSuccess { hierarchyEntries ->
                builder.setHierarchy(hierarchyEntries.mapIndexed { index, entry ->
                    DiagramHierarchyItem(entry.title, entry.depth, index, entry.layers == currentLayers)
                }) { index ->
                    hierarchyEntries.getOrNull(index)?.takeIf { it.layers != currentLayers }?.let { destination ->
                        val root = service.repository.getRecord(rootRecordId)
                        var destinationFile = service.editorFile(root).apply { parentFile = null }
                        destination.layers.forEach { layer ->
                            val child = service.editorFile(service.repository.getRecord(layer.recordId), layer.nodeId)
                            child.parentFile = destinationFile
                            destinationFile = child
                        }
                        if (destinationFile != virtualFile) {
                            openExistingEditor(project, destinationFile)
                            FileEditorManager.getInstance(project).closeFile(virtualFile)
                        }
                    }
                }

            }
        }
        fun refreshHierarchy() = hierarchyLoader.request { graphHierarchyEntries(service.repository, rootRecordId) }
        hierarchyChanged = service.onChanged(::refreshHierarchy)
        refreshHierarchy()
        refreshHeader()
    }

    private fun refreshHeader() {
        if (virtualFile.parentFile == null) panel.remove(header) else panel.add(header, BorderLayout.NORTH)
        panel.revalidate()
        panel.repaint()
    }

    fun disableAutomaticSourceNavigation() { builder.navigationPinned = true }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = builder.jComponent
    override fun getName(): String = virtualFile.name
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !disposed && record.revision == virtualFile.revision
    override fun getFile(): VirtualFile = virtualFile
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changes.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changes.removePropertyChangeListener(listener)
    override fun dispose() {
        disposed = true
        navigation.close()
        questionSelection.close()
        copySelection.close()
        content.close()
        hierarchyChanged.close()
        hierarchyLoader.close()
        fonts.close()
        Disposer.dispose(builder)
        panel.removeAll()
    }
}

internal fun openRecordInEditor(
    project: Project,
    record: UnderstandingRecord,
    questionNodeId: String? = null,
    replace: UnderstandingVirtualFile? = null,
    resetSourceNavigation: Boolean = false,
) {
    val file = project.getService(UnderstandingService::class.java).editorFile(record, questionNodeId)
    if (file == replace) return
    val service = project.getService(UnderstandingService::class.java)
    val hierarchyParent = if (replace == null && questionNodeId != null) {
        val chain = mutableListOf<UnderstandingVirtualFile>()
        service.panelRootRecordId?.let { id ->
            runCatching { service.repository.getRecord(id) }.getOrNull()?.let { chain += service.editorFile(it) }
        }
        service.panelLayers.forEach { layer ->
            val source = runCatching { service.repository.getRecord(layer.recordId) }.getOrNull()
            if (source != null) {
                val child = service.editorFile(source, layer.nodeId)
                child.parentFile = chain.lastOrNull()
                chain += child
            }
        }
        chain.dropLast(1).lastOrNull()
    } else null
    file.parentFile = replace ?: hierarchyParent
    openExistingEditor(project, file, resetSourceNavigation)
    if (replace != null) FileEditorManager.getInstance(project).closeFile(replace)
}

private fun openExistingEditor(
    project: Project,
    file: UnderstandingVirtualFile,
    resetSourceNavigation: Boolean = false,
) {
    val editors = FileEditorManager.getInstance(project).openFile(file, true)
    if (resetSourceNavigation) editors.filterIsInstance<UnderstandingFileEditor>().forEach {
        it.disableAutomaticSourceNavigation()
    }
}
