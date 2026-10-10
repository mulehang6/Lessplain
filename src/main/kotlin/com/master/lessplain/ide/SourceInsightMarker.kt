package com.master.lessplain.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.master.lessplain.model.SourceAnchor
import java.nio.file.Path
import javax.swing.SwingUtilities

/** Owns only the inlays and highlighters created for the active Understanding node. */
internal class SourceInsightMarker(private val project: Project, parent: Disposable) : Disposable {
    private data class Target(
        val document: Document,
        val start: Int,
        val end: Int,
        val label: String,
        val navigation: SourceInlayNavigation,
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

    /** Resolves the evidence without opening a source editor. */
    fun show(anchor: SourceAnchor?, label: String, navigation: SourceInlayNavigation): LocatedSource? {
        check(SwingUtilities.isEventDispatchThread())
        clear()
        if (anchor == null || disposed) return null
        val located = locateSource(project, anchor)
        val start = located.offset ?: return located
        val root = Path.of(project.basePath ?: return located).toAbsolutePath().normalize()
        val path = root.resolve(anchor.path).normalize()
        if (!path.startsWith(root)) return located
        val document = ApplicationManager.getApplication().runReadAction<Document?> {
            val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@runReadAction null
            FileDocumentManager.getInstance().getDocument(file)
        } ?: return located
        val end = (start + anchor.text.length).coerceAtMost(document.textLength)
        if (end <= start) return located
        target = Target(document, start, end, label.replace('\n', ' '), navigation)
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
        target = null
        markers.values.forEach(EditorSourceInsightMarker::dispose)
        markers.clear()
    }

    private fun attach(editor: Editor, value: Target) {
        if (editor.project != project || editor.document !== value.document || editor in markers) return
        EditorSourceInsightMarker(editor, value.start, value.end, value.label, value.navigation).takeIf { it.isInstalled }?.let {
            markers[editor] = it
        }
    }

    override fun dispose() {
        disposed = true
        if (SwingUtilities.isEventDispatchThread()) clear()
        else SwingUtilities.invokeLater { clear() }
    }
}

internal data class SourceInlayNavigation(
    val canPrevious: Boolean,
    val canNext: Boolean,
    val previous: () -> Unit,
    val next: () -> Unit,
    val cancelChoices: () -> Unit = {},
)
