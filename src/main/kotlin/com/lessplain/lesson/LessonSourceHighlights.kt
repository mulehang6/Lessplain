package com.lessplain.lesson

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import java.awt.Color
import java.awt.Font
import java.awt.Point
import java.nio.file.Path
import javax.swing.SwingUtilities
import javax.swing.Timer

/** 不占 gutter。每个编辑器只维护自己的 range marker 和细线，关闭时全部释放。 */
internal class LessonSourceHighlights(private val project: Project, private val service: LessonService) : AutoCloseable {
    private data class Tracking(val marker: RangeMarker, val text: String)
    private class EditorMarks {
        val tracks = mutableMapOf<LessonSourceFocus, Tracking>()
        val lines = mutableListOf<RangeHighlighter>()
        var entries: List<Pair<IntRange, LessonSourceEntry>> = emptyList()
        fun close() { lines.forEach { it.dispose() }; tracks.values.forEach { it.marker.dispose() } }
    }
    private val disposable = Disposer.newDisposable("Lessplain source references")
    private val editors = mutableMapOf<Editor, EditorMarks>()
    private var closed = false
    private var refreshQueued = false
    private var popup: JBPopup? = null
    private var hoverEditor: Editor? = null
    private var hoverEntries: List<LessonSourceEntry> = emptyList()
    private var hoverPoint = Point()
    private val hideTimer = Timer(350) { hidePopup() }.apply { isRepeats = false }
    private val hoverTimer = Timer(450) { showPreview() }.apply { isRepeats = false }

    init {
        val factory = EditorFactory.getInstance()
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) { if (event.editor.project == project) scheduleRefresh() }
            override fun editorReleased(event: EditorFactoryEvent) {
                editors.remove(event.editor)?.close()
                if (hoverEditor == event.editor) { hidePopup(); hoverEditor = null; hoverEntries = emptyList() }
            }
        }, disposable)
        factory.eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (hoverEditor?.document == event.document) hidePopup()
                if (editors.keys.any { it.document == event.document }) scheduleRefresh()
            }
        }, disposable)
        factory.eventMulticaster.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(event: EditorMouseEvent) {
                if (hoverEditor == event.editor) { hoverTimer.stop(); hideTimer.restart() }
            }
            override fun mousePressed(event: EditorMouseEvent) {
                if (hoverEditor == event.editor) hidePopup()
            }
        }, disposable)
        factory.eventMulticaster.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val editor = event.editor
                val marks = editors[editor] ?: return
                if (event.area != EditorMouseEventArea.EDITING_AREA || event.mouseEvent.modifiersEx != 0) {
                    hoverTimer.stop(); hideTimer.restart(); return
                }
                val position = editor.xyToLogicalPosition(event.mouseEvent.point)
                val offset = editor.logicalPositionToOffset(position)
                if (editor.offsetToLogicalPosition(offset) != position) {
                    hoverTimer.stop(); hideTimer.restart(); return
                }
                val hits = lessonEntriesAt(marks.entries, offset)
                if (hits.isEmpty()) { hoverTimer.stop(); hideTimer.restart(); return }
                hideTimer.stop()
                if (editor == hoverEditor && hits == hoverEntries && popup?.isVisible == true) return
                if (hits == hoverEntries && editor == hoverEditor && hoverTimer.isRunning) return
                hidePopup()
                hoverEditor = editor; hoverEntries = hits
                hoverPoint = Point(event.mouseEvent.x, event.mouseEvent.y + editor.lineHeight)
                hoverTimer.restart()
            }
        }, disposable)
    }

    private fun scheduleRefresh() {
        if (closed || refreshQueued) return
        refreshQueued = true
        SwingUtilities.invokeLater { refreshQueued = false; if (!closed) refresh() }
    }

    fun refresh() {
        if (closed || project.isDisposed) return
        if (popup?.isVisible == true) hidePopup()
        lessonUiRead {
            val references = lessonSourceEntries(service.snapshot)
            val live = EditorFactory.getInstance().allEditors.filter { !it.isDisposed && !it.isViewer && it.project == project }
            editors.keys.filter { it !in live }.toList().forEach { editors.remove(it)?.close() }
            live.forEach { editor ->
                val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@forEach
                val base = project.basePath ?: return@forEach
                val root = Path.of(base).toAbsolutePath().normalize()
                val path = Path.of(file.path).toAbsolutePath().normalize()
                if (file.fileSystem.protocol != "file" || !path.startsWith(root)) return@forEach
                val relative = root.relativize(path).toString().replace('\\', '/')
                update(editor, references.filter { it.focus.anchor.path == relative })
            }
        }
    }

    private fun update(editor: Editor, references: List<LessonSourceEntry>) {
        val marks = editors.getOrPut(editor) { EditorMarks() }
        val focuses = references.map { it.focus }.toSet()
        marks.tracks.keys.filter { it !in focuses }.toList().forEach { marks.tracks.remove(it)?.marker?.dispose() }
        val source = editor.document.text
        val located = references.mapNotNull { entry ->
            val existing = marks.tracks[entry.focus]
            val range = if (existing != null) {
                val marker = existing.marker
                if (marker.isValid && marker.endOffset <= source.length &&
                    source.substring(marker.startOffset, marker.endOffset) == existing.text) marker.startOffset until marker.endOffset
                else null
            } else resolveLessonFocus(source, entry.focus)
            if (range == null) return@mapNotNull null
            if (existing == null) marks.tracks[entry.focus] = Tracking(
                editor.document.createRangeMarker(range.first, range.last + 1), focusText(entry.focus))
            range to entry
        }
        marks.lines.forEach { it.dispose() }; marks.lines.clear()
        located.groupBy { it.first }.forEach { (range, entries) ->
            val hasNotes = entries.any { it.second.annotations.isNotEmpty() }
            marks.lines += editor.markupModel.addRangeHighlighter(range.first, range.last + 1,
                HighlighterLayer.ADDITIONAL_SYNTAX, lessonUnderlineAttributes(hasNotes), HighlighterTargetArea.EXACT_RANGE)
        }
        marks.entries = located
    }

    /** 活跃文档优先采用仍然有效的跟踪范围，跳转与下划线使用同一位置。 */
    fun currentRange(document: com.intellij.openapi.editor.Document, focus: LessonSourceFocus): IntRange? =
        editors.entries.firstNotNullOfOrNull { (editor, marks) ->
            if (editor.document != document) return@firstNotNullOfOrNull null
            val tracked = marks.tracks[focus] ?: return@firstNotNullOfOrNull null
            val marker = tracked.marker
            if (marker.isValid && marker.endOffset <= document.textLength &&
                document.getText(com.intellij.openapi.util.TextRange(marker.startOffset, marker.endOffset)) == tracked.text)
                marker.startOffset until marker.endOffset else null
        }

    private fun showPreview() {
        val editor = hoverEditor ?: return
        if (closed || editor.isDisposed || !editor.contentComponent.isShowing || hoverEntries.isEmpty()) return
        popup = lessonSourcePreview(service, hoverEntries, { hideTimer.stop() }, { hideTimer.restart() }).also {
            it.show(RelativePoint(editor.contentComponent, hoverPoint))
        }
    }

    private fun hidePopup() {
        hoverTimer.stop(); hideTimer.stop()
        popup?.cancel(); popup = null
    }

    override fun close() {
        closed = true
        hidePopup()
        editors.values.forEach { it.close() }; editors.clear()
        Disposer.dispose(disposable)
    }
}

internal fun lessonUnderlineAttributes(hasNotes: Boolean): TextAttributes {
    val base = JBColor(Color(0x3E, 0x6F, 0xA8), Color(0x78, 0xA9, 0xE0))
    val effect = Color(base.red, base.green, base.blue, if (hasNotes) 230 else 110)
    return TextAttributes(null, null, effect, EffectType.LINE_UNDERSCORE, Font.PLAIN)
}

/** 重开项目即读取持久教材，不要求先打开材料列表。 */
class LessonStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) { project.getService(LessonService::class.java) }
}
