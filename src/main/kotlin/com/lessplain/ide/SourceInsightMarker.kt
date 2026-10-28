package com.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.lessplain.model.SourceAnchor
import java.nio.file.Path
import javax.swing.SwingUtilities

/** 只管理为当前理解节点创建的 inline 和高亮。 */
internal class SourceInsightMarker(private val project: Project, parent: Disposable) : Disposable {
    private data class Target(
        val document: Document,
        val range: RangeMarker,
        val text: String,
        val label: String,
        val navigation: SourceInlayNavigation,
        val binding: SourceNodeBinding?,
    )

    private val factory = EditorFactory.getInstance()
    private val markers = mutableMapOf<Editor, EditorSourceInsightMarker>()
    private var target: Target? = null
    private var disposed = false

    init {
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed && target != null && factory.getAllEditors().any { it === editor })
                        uiRead { target?.let { attach(editor, it) } }
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
        binding: SourceNodeBinding? = null
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
        val end = start + anchor.text.length
        if (start < 0 || end > document.textLength || end <= start) return located
        if (sourceExcerptChanged(document.charsSequence, start, end, anchor.text)) return located
        target = Target(document, document.createRangeMarker(start, end), anchor.text, label.replace('\n', ' '), navigation, binding)
        refreshOpenEditors()
        return located
    }

    fun refreshOpenEditors() {
        check(SwingUtilities.isEventDispatchThread())
        val value = target ?: return
        factory.getEditors(value.document, project).forEach { attach(it, value) }
    }

    fun clear() {
        check(SwingUtilities.isEventDispatchThread())
        target?.range?.dispose()
        target = null
        markers.values.forEach(EditorSourceInsightMarker::dispose)
        markers.clear()
    }

    private fun attach(editor: Editor, value: Target) {
        if (target !== value || editor.project != project || editor.document !== value.document) return
        val range = value.range
        if (!range.isValid || sourceExcerptChanged(value.document.charsSequence, range.startOffset, range.endOffset, value.text)) {
            clear()
            value.navigation.evidenceChanged()
            return
        }
        if (editor in markers) return
        EditorSourceInsightMarker(
            editor,
            range.startOffset,
            range.endOffset,
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
        else ApplicationManager.getApplication().invokeLater { clear() }
    }
}

internal data class SourceInlayNavigation(
    val canPrevious: Boolean,
    val canNext: Boolean,
    val previous: () -> Unit,
    val next: () -> Unit,
    val cancelChoices: () -> Unit = {},
    val evidenceChanged: () -> Unit = {},
)
