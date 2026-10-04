package com.master.understanding.diagram

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
import com.master.understanding.ide.UnderstandingService
import com.master.understanding.ide.GraphLayerKey
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.core.StaticExplorer
import com.master.understanding.ui.buildQuestionCanvas
import com.master.understanding.ui.FollowUpLayer
import com.master.understanding.ui.graphHierarchyEntries
import com.master.understanding.ui.questionsForNode
import com.master.understanding.ui.bindContentActions
import com.master.understanding.ui.SourceNode
import com.master.understanding.storage.pendingQuestionsForNode
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel

/** The editor tab and tool window each render the same immutable record revision. */
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

    override fun getEditorTypeId(): String = "master-understanding-diagram"
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
        bindGraphState(service.graphStates, graphKey)
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
    private val content = builder.bindContentActions(service, { nodeId ->
        questionCanvas?.sources?.get(nodeId) ?: if (questionCanvas == null) SourceNode(record, nodeId) else null
    }, { id -> questionCanvas?.clusterSources?.get(id) }, titleRecord = record.takeIf { questionCanvas == null })

    private val changed = service.onChanged {
        val records = questionCanvas?.sources?.values?.map { it.record }.orEmpty() + record
        if (records.any { snapshot ->
            runCatching { service.repository.getRecord(snapshot.id).revision != snapshot.revision }.getOrDefault(true)
        }) navigation.close()
        updateQuestionCounts()
    }
    private val backButton = JButton("‹ 返回").apply {
        setToolTipText(HtmlChunk.text("返回上一级理解图"))
        addActionListener {
            virtualFile.parentFile?.let { parent ->
                openExistingEditor(project, parent)
                FileEditorManager.getInstance(project).closeFile(virtualFile)
            }
        }
    }
    private val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
        add(backButton)
    }
    private val panel = JPanel(BorderLayout()).apply {
        add(builder.jComponent, BorderLayout.CENTER)
    }
    private var disposed = false

    init {
        val lineage = generateSequence(virtualFile) { it.parentFile }.toList().asReversed()
        val rootRecordId = lineage.first().recordId
        val currentLayers = lineage.drop(1).mapNotNull { file ->
            file.questionNodeId?.let { FollowUpLayer(file.recordId, it) }
        }
        val hierarchyEntries = graphHierarchyEntries(service.repository, rootRecordId)
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
        refreshHeader()
        updateQuestionCounts()
    }

    private fun refreshHeader() {
        if (virtualFile.parentFile == null) panel.remove(header) else panel.add(header, BorderLayout.NORTH)
        panel.revalidate()
        panel.repaint()
    }

    private fun updateQuestionCounts() {
        if (questionCanvas != null) {
            val counts = questionCanvas.sources.mapNotNull { (id, source) ->
                if (source.record.id == record.id) return@mapNotNull null
                val count = questionsForNode(service, source.record, source.nodeId).size
                id.takeIf { count > 0 }?.let { it to count }
            }.toMap()
            builder.setQuestionCounts(counts)
            builder.setPendingNoteCounts(questionCanvas.sources.mapNotNull { (id, source) ->
                val count = service.repository.pendingQuestionsForNode(source.record.id, source.nodeId).size
                id.takeIf { count > 0 }?.let { it to count }
            }.toMap())
            return
        }
        val counts = service.repository.questionsForRecord(record.id).filter {
            service.repository.recordForQuestion(it.id) != null
        }.groupingBy { question ->
            service.repository.getContext(question.contextToken).nodeId
        }.eachCount().filterKeys { it != null }.mapKeys { it.key!! }
        builder.setQuestionCounts(counts)
        builder.setPendingNoteCounts(record.graph.nodes.mapNotNull { node ->
            val count = service.repository.pendingQuestionsForNode(record.id, node.id).size
            node.id.takeIf { count > 0 }?.let { it to count }
        }.toMap())
    }

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
        changed.close()
        Disposer.dispose(builder)
        panel.removeAll()
    }
}

internal fun openRecordInEditor(
    project: Project,
    record: UnderstandingRecord,
    questionNodeId: String? = null,
    replace: UnderstandingVirtualFile? = null,
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
    openExistingEditor(project, file)
    if (replace != null) FileEditorManager.getInstance(project).closeFile(replace)
}

private fun openExistingEditor(
    project: Project,
    file: UnderstandingVirtualFile,
) {
    FileEditorManager.getInstance(project).openFile(file, true)
}
