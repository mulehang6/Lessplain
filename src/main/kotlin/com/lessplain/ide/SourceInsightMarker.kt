package com.lessplain.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.lessplain.model.SourceAnchor
import com.lessplain.experiment.model.EditorSourceBinding
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/** 只管理为当前理解节点创建的 inline 和高亮。 */
internal class SourceInsightMarker(private val project: Project, parent: Disposable) : Disposable {
    private data class Target(
        val document: Document,
        val start: Int,
        val end: Int,
        val label: String,
        val navigation: SourceInlayNavigation,
        val binding: EditorSourceBinding?,
    )

    private val factory = EditorFactory.getInstance()
    private val markers = mutableMapOf<Editor, EditorSourceInsightMarker>()
    private var target: Target? = null
    private var disposed = false

    init {
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                SwingUtilities.invokeLater {
                    if (!disposed && factory.getAllEditors().any { it === editor }) target?.let { attach(editor, it) }
                }
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                markers.remove(event.editor)?.dispose()
            }
        }, parent)
    }

    /** 不打开源码编辑器即可定位证据。 */
    fun show(
        anchor: SourceAnchor?, resolution: SourceResolution, label: String, navigation: SourceInlayNavigation,
        binding: EditorSourceBinding? = null
    ): LocatedSource? {
        check(SwingUtilities.isEventDispatchThread())
        clear()
        if (anchor == null || disposed) return null
        if (!resolution.isCurrent()) return null
        val located = resolution.located
        if (located.evidence == EvidenceStatus.CHANGED) return located
        val start = located.offset ?: return located
        val root = Path.of(project.basePath ?: return located).toAbsolutePath().normalize()
        val path = root.resolve(anchor.path).normalize()
        if (!path.startsWith(root)) return located
        val document = resolution.document ?: return located
        val end = (start + anchor.text.length).coerceAtMost(document.textLength)
        if (end <= start) return located
        target = Target(document, start, end, label.replace('\n', ' '), navigation, binding)
        refreshOpenEditors()
        return located
    }

    fun refreshOpenEditors() {
        check(SwingUtilities.isEventDispatchThread())
        val value = target ?: return
        factory.getEditors(value.document, project).forEach { attach(it, value) }
    }

    /** 当前源码编辑器中的真实选区沿用 marker 节点身份，精确选区仍由来源校验负责。 */
    fun bindingFor(editor: Editor): EditorSourceBinding? {
        if (editor.isDisposed) return null
        val marker = markers[editor] ?: return null
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return null
        return marker.bindingForSelection(selection.selectionStart, selection.selectionEnd)
    }

    /** 画布只更新与当前源码 binding 同 host、同 view revision 的步进能力。 */
    fun updateNavigationState(
        hostId: String,
        viewRef: String,
        revision: Int,
        elementId: String,
        nodeId: String,
        canPrevious: Boolean,
        canNext: Boolean,
    ) {
        check(SwingUtilities.isEventDispatchThread())
        val value = target ?: return
        if (bindingForNavigation(hostId, viewRef, revision, elementId, nodeId) == null) return
        value.navigation.updateCapabilities(canPrevious, canNext)
    }

    fun bindingForNavigation(
        hostId: String,
        viewRef: String,
        revision: Int,
        elementId: String,
        nodeId: String,
    ): EditorSourceBinding? {
        check(SwingUtilities.isEventDispatchThread())
        return target?.binding?.takeIf {
            it.hostId == hostId && it.viewRef == viewRef && it.revision == revision &&
                it.elementId == elementId && it.nodeId == nodeId
        }
    }

    /** 关闭宿主或页面切换时撤销旧箭头；点击回调仍会做服务端校验。 */
    fun invalidateHost(hostId: String) {
        check(SwingUtilities.isEventDispatchThread())
        val value = target ?: return
        if (value.binding?.hostId == hostId) value.navigation.updateCapabilities(false, false)
    }

    fun invalidateBinding(binding: EditorSourceBinding) {
        check(SwingUtilities.isEventDispatchThread())
        val value = target ?: return
        if (value.binding == binding) value.navigation.updateCapabilities(false, false)
    }

    fun invalidateRevisions(revisions: Map<String, Int>) {
        check(SwingUtilities.isEventDispatchThread())
        val value = target ?: return
        val binding = value.binding ?: return
        if (revisions[binding.viewRef] != binding.revision) value.navigation.updateCapabilities(false, false)
    }

    fun clear() {
        check(SwingUtilities.isEventDispatchThread())
        target = null
        markers.values.forEach(EditorSourceInsightMarker::dispose)
        markers.clear()
    }

    private fun attach(editor: Editor, value: Target) {
        if (editor.project != project || editor.document !== value.document || editor in markers) return
        EditorSourceInsightMarker(
            editor,
            value.start,
            value.end,
            value.label,
            value.navigation,
            value.binding
        ).takeIf { it.isInstalled }?.let {
            markers[editor] = it
        }
    }

    override fun dispose() {
        disposed = true
        if (SwingUtilities.isEventDispatchThread()) clear()
        else SwingUtilities.invokeLater { clear() }
    }
}

internal class SourceInlayNavigation(
    canPrevious: Boolean,
    canNext: Boolean,
    val previous: () -> Unit,
    val next: () -> Unit,
    val cancelChoices: () -> Unit = {},
    val evidenceChanged: () -> Unit = {},
) {
    @Volatile var canPrevious: Boolean = canPrevious
        private set
    @Volatile var canNext: Boolean = canNext
        private set

    private val capabilityListeners = CopyOnWriteArrayList<() -> Unit>()

    fun updateCapabilities(canPrevious: Boolean, canNext: Boolean) {
        if (this.canPrevious == canPrevious && this.canNext == canNext) return
        this.canPrevious = canPrevious
        this.canNext = canNext
        capabilityListeners.forEach { it() }
    }

    fun onCapabilitiesChanged(listener: () -> Unit): AutoCloseable {
        capabilityListeners += listener
        return AutoCloseable { capabilityListeners -= listener }
    }
}
