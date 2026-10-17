package com.lessplain.experiment.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.lessplain.experiment.core.digest
import com.lessplain.experiment.model.*
import com.lessplain.model.SourceSelectionRange
import com.lessplain.ui.UnderstandingUiStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities

/** 只读内存文件不冒充项目源码，也不进入真实路径解析器。 */
internal class SampleVirtualFile(var location: SampleLocation) : LightVirtualFile(
    sampleFileName(location.sample),
    FileTypeManager.getInstance().getFileTypeByFileName(sampleFileName(location.sample)),
    location.sample.code,
) {
    var current = true
    init { isWritable = false }
}

internal fun sampleFileName(sample: CodeSample): String {
    val extension = when (sample.language.lowercase()) {
        "kotlin" -> "kt"
        "javascript" -> "js"
        "typescript" -> "ts"
        "python" -> "py"
        else -> sample.language.lowercase()
    }
    val title = sample.title.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
    return if (title.endsWith(".$extension")) title else "$title.$extension"
}

/** 原生样例标签的所有权、当前定位及失效处理均属于项目服务。 */
internal class SampleNavigation(private val project: Project) : Disposable {
    private val files = mutableMapOf<Pair<String, String>, SampleVirtualFile>()
    private var publishedViews: Map<String, ViewRecord>? = null
    private var highlight: RangeHighlighter? = null
    private var highlightedFile: VirtualFile? = null

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file !is SampleVirtualFile) return
                files.entries.removeIf { it.value === file }
                if (highlightedFile === file) clearHighlight()
            }
        })
    }

    suspend fun open(location: SampleLocation) = withContext(Dispatchers.EDT) {
        check(publishedViews?.let { it[location.viewRef]?.document?.codeSamples.orEmpty()[location.target.id] != location.sample } != true) {
            "教学代码已变化或页面已删除，请重新定位"
        }
        val key = location.viewRef to location.target.id
        val previous = files[key]
        val file = if (previous?.location?.sample == location.sample && previous.current) previous.apply { this.location = location }
            else SampleVirtualFile(location).also { previous?.current = false; files[key] = it }
        FileEditorManager.getInstance(project).openFile(file, true)
        OpenFileDescriptor(project, file, location.target.startLine - 1, 0).navigate(true)
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@withContext
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@withContext
        if (editor.document !== document) return@withContext
        clearHighlight()
        val start = document.getLineStartOffset(location.target.startLine - 1)
        val end = document.getLineEndOffset((location.target.endLine ?: location.target.startLine) - 1)
        highlight = editor.markupModel.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1,
            TextAttributes().apply { backgroundColor = UnderstandingUiStyle.focusSurface }, HighlighterTargetArea.LINES_IN_RANGE)
        highlightedFile = file
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    fun location(editor: Editor): SampleLocation? {
        val file = FileDocumentManager.getInstance().getFile(editor.document) as? SampleVirtualFile ?: return null
        return file.location.takeIf { file.current }
    }

    fun invalidate(snapshot: AppSnapshot) {
        check(SwingUtilities.isEventDispatchThread())
        val views = snapshot.views.associateBy { it.ref }
        publishedViews = views
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.filterIsInstance<SampleVirtualFile>().forEach { file ->
            val view = views[file.location.viewRef]
            if (view == null || view.document.codeSamples.orEmpty()[file.location.target.id] != file.location.sample) {
                file.current = false
                if (highlightedFile === file) clearHighlight()
                if (view == null) manager.closeFile(file)
            }
        }
    }

    private fun clearHighlight() { highlight?.dispose(); highlight = null; highlightedFile = null }
    override fun dispose() { clearHighlight(); files.values.forEach { it.current = false }; files.clear() }
}

/** 只使用打开时的样例和当前选区；正文变更由保存端再次拒绝。 */
internal fun SampleLocation.selection(start: Int, end: Int): ViewOrigin {
    val lines = sample.code.lines()
    val targetStart = lines.take(target.startLine - 1).sumOf { it.length + 1 }
    val targetEnd = lines.take(target.endLine ?: target.startLine).sumOf { it.length + 1 } - 1
    val captured = if (origin.nodeId != null && (start < targetStart || end > targetEnd))
        origin.copy(elementId = null, nodeId = null, nodeLabel = null) else origin
    return captured.copy(entry = ViewOriginEntry.SAMPLE_SELECTION,
        sample = SampleSelection(target.id, sample, digest(sample.code), SourceSelectionRange(start, end)))
}
