package com.lessplain.lesson

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.lessplain.storage.sha256
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.table.DefaultTableModel
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.installNativeMenu
import com.lessplain.ui.showNativeMenu

internal val lessonEditorSelectionKey = Key.create<(Boolean) -> Unit>("lessplain.lesson.selection")

/** 一个块只负责显示及产生精确选区，回答与笔记由外层教材视图组织。 */
internal class LessonBlockViews(
    private val project: Project,
    private val service: LessonService,
    private val record: LessonRecord,
    private val resources: MutableList<AutoCloseable>,
    private val annotate: (LessonTarget, Boolean) -> Unit,
    private val reflow: (() -> Unit) -> Unit,
) {
    fun create(block: LessonBlock, annotationId: String?): JComponent = when (block.kind) {
        "text" -> text(block, annotationId)
        "code" -> code(block, annotationId)
        "table" -> table(block, annotationId)
        "diagram" -> figure(block, annotationId)
        else -> selectableLessonText("无法显示的内容类型：${block.kind}")
    }

    private fun target(block: LessonBlock, owner: String?, text: String, start: Int, end: Int) =
        LessonTarget(block.id, owner, start, end, text.substring(start, end), sha256(text))

    private fun text(block: LessonBlock, owner: String?): JComponent = lessonTextView(block).apply {
        installNativeMenu(this) { menuItems {
            val start = selectionStart.takeIf { selectionEnd > it } ?: 0
            val end = selectionEnd.takeIf { it > start } ?: text.length
            target(block, owner, text, start, end)
        } }
    }

    private fun code(block: LessonBlock, owner: String?): JComponent = lessonUiRead {
        val example = block.exampleId?.let { id -> record.examples.firstOrNull { it.id == id } }
        val virtual = example?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(service.workspace.examplePath(record, it)) }
        val document = virtual?.let { FileDocumentManager.getInstance().getDocument(it) }
            ?: EditorFactory.getInstance().createDocument(block.text)
        val editor = EditorFactory.getInstance().createViewer(document, project) as EditorEx
        editor.highlighter = if (virtual != null) EditorHighlighterFactory.getInstance().createEditorHighlighter(project, virtual)
        else EditorHighlighterFactory.getInstance().createEditorHighlighter(
            project, FileTypeManager.getInstance().getFileTypeByExtension(if (block.language == "kotlin") "kt" else block.language),
        )
        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isUseSoftWraps = true
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isWhitespacesShown = false
            isCaretRowShown = false
            isShowIntentionBulb = false
        }
        editor.setCaretEnabled(false)
        editor.setVerticalScrollbarVisible(false)
        editor.putUserData(lessonEditorSelectionKey) { question ->
            val selection = editor.selectionModel
            val start = if (selection.hasSelection()) selection.selectionStart else 0
            val end = if (selection.hasSelection()) selection.selectionEnd else document.textLength
            if (end > start) annotate(target(block, owner, document.text, start, end), question)
        }
        resources += AutoCloseable {
            editor.putUserData(lessonEditorSelectionKey, null)
            EditorFactory.getInstance().releaseEditor(editor)
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(javax.swing.JLabel(if (example != null) "Kotlin · 可运行示例" else if (block.language == "pseudo") "伪代码" else block.language))
            block.source?.let { source ->
                add(JButton("查看源码").apply { addActionListener { service.navigateSource(source) } })
            }
            if (example != null) {
                val runtime = LessonScriptRuntime(project, service)
                add(JButton("完整示例").apply { addActionListener { runtime.open(record, example) } })
                add(JButton("运行").apply { addActionListener { runtime.open(record, example, "Run") } })
                add(JButton("Debug").apply { addActionListener { runtime.open(record, example, "Debug") } })
            }
        }
        object : JPanel(BorderLayout(0, JBUI.scale(5))) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(LessonCodePanel(editor, reflow), BorderLayout.CENTER)
        }
    }

    private fun table(block: LessonBlock, owner: String?): JComponent {
        val table = createLessonTable(block)
        installNativeMenu(table) { menuItems {
            val row = table.selectedRow
            val column = table.selectedColumn
            if (row < 0 || column < 0) null else {
                val value = block.rows[row][column]
                target(block, owner, value, 0, value.length).copy(row = row, column = column)
            }
        } }
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(event)) {
                    val row = table.rowAtPoint(event.point)
                    val column = table.columnAtPoint(event.point)
                    if (row >= 0 && column >= 0) table.changeSelection(row, column, false, false)
                }
            }
        })
        return contentScroll(table)
    }

    private fun figure(block: LessonBlock, owner: String?): JComponent {
        val figure = LessonFigureView(service, requireNotNull(block.graph), reflow) { id, label, event ->
            showNativeMenu(event.component, menuItems { target(block, owner, label, 0, label.length).copy(objectId = id) }, event.point)
        }
        resources += figure
        return contentScroll(figure, 600)
    }

    private fun menuItems(selection: () -> LessonTarget?): List<NativeMenuItem> = listOf(
        NativeMenuItem("追问此处") { selection()?.let { annotate(it, true) } },
        NativeMenuItem("写下我的理解") { selection()?.let { annotate(it, false) } },
    )
}

/** 普通表格完整展开；图与笔记可限制高度，超宽内容保留横向滚动。 */
internal fun contentScroll(content: JComponent, heightLimit: Int? = null): JScrollPane = object : JScrollPane(content) {
    override fun getPreferredSize(): Dimension {
        val height = content.preferredSize.height + (columnHeader?.preferredSize?.height ?: 0) +
            if (horizontalScrollBar.isVisible) horizontalScrollBar.preferredSize.height else 0
        return Dimension(640, height.coerceAtLeast(1).let { value -> heightLimit?.let { value.coerceAtMost(JBUI.scale(it)) } ?: value })
    }
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}.apply {
    border = JBUI.Borders.empty()
    if (heightLimit == null) verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
}

/** 单元格保留整段解释，随教材宽度换行，不把长定义截成一行。 */
internal fun createLessonTable(block: LessonBlock): JBTable {
    val model = object : DefaultTableModel(block.rows.map { it.toTypedArray() }.toTypedArray(), block.columns.toTypedArray()) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    return object : JBTable(model) {
        override fun doLayout() {
            super.doLayout()
            for (row in 0 until rowCount) {
                val height = (0 until columnCount).maxOfOrNull { column ->
                    val renderer = prepareRenderer(getCellRenderer(row, column), row, column)
                    renderer.setSize(columnModel.getColumn(column).width.coerceAtLeast(20), Short.MAX_VALUE.toInt())
                    renderer.preferredSize.height
                } ?: JBUI.scale(30)
                if (getRowHeight(row) != height) setRowHeight(row, height)
            }
        }
    }.apply {
        cellSelectionEnabled = true
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        setDefaultRenderer(Any::class.java) { table, value, selected, _, _, _ ->
            JTextArea(value?.toString().orEmpty()).apply {
                lineWrap = true; wrapStyleWord = true
                font = table.font
                border = JBUI.Borders.empty(6)
                foreground = if (selected) table.selectionForeground else table.foreground
                background = if (selected) table.selectionBackground else table.background
            }
        }
    }
}
