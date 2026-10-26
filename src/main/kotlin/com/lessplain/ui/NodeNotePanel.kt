package com.lessplain.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingQuestion
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel

internal data class NodeNoteDraft(val questionId: String, val text: String)

/** 后台读取的冻结源码随问题传入，不在 Swing 回调中访问仓库或当前文件。 */
internal data class NodeNoteState(
    val notes: List<UnderstandingQuestion> = emptyList(),
    val drafts: List<NodeNoteDraft> = emptyList(),
    val anchors: Map<String, SourceAnchor> = emptyMap(),
    val error: String? = null,
)

/** 每页直接显示完整注释与冻结代码；切页仅替换详情，不重建画布。 */
internal class NodeNotePanel(
    private val project: Project,
    private val readOnly: Boolean,
    add: () -> Unit,
    private val edit: (UnderstandingQuestion) -> Unit,
    private val continueDraft: (String) -> Unit,
    private val delete: (UnderstandingQuestion) -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))), AutoCloseable {
    val snapshot = QuestionSnapshotPane(project)
    private val counter = JLabel()
    private val error = JLabel()
    private var state = NodeNoteState()
    private var pages = emptyList<Page>()
    private var pageIndex = 0
    private var displayed: Page? = null
    private var closed = false
    private val fonts = CanvasUiFonts(listOf(counter, error))
    private data class Page(val id: String, val note: UnderstandingQuestion?, val draft: NodeNoteDraft?, val anchor: SourceAnchor?)
    val previous = popupIconButton(AllIcons.Actions.Back, "上一条注释") { page(-1) }
    val next = popupIconButton(AllIcons.Actions.Forward, "下一条注释") { page(1) }
    val editButton = popupIconButton(AllIcons.Actions.Edit, "编辑这条注释") {
        current()?.let { page -> page.draft?.let { continueDraft(it.questionId) } ?: page.note?.let(edit) }
    }
    val deleteButton = popupIconButton(AllIcons.Actions.GC, "删除这条注释") { current()?.note?.let(delete) }
    val addButton = popupIconButton(AllIcons.General.Add, "添加注释", action = add)

    init {
        border = JBUI.Borders.empty(PopupUi.PADDING)
        preferredSize = JBUI.size(520, 420)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(PopupUi.ICON_GAP), 0)).apply {
                isOpaque = false
                listOf(previous, next, addButton, editButton, deleteButton).forEach(::add)
            }, BorderLayout.WEST)
            add(counter, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(snapshot, BorderLayout.CENTER)
        add(error, BorderLayout.SOUTH)
    }

    fun refresh(value: NodeNoteState) {
        val id = current()?.id
        state = value
        pages = value.notes.map { note -> Page(note.id, note, value.drafts.firstOrNull { it.questionId == note.id }, value.anchors[note.id]) } +
            value.drafts.filter { it.questionId.isEmpty() }.map { Page("new", null, it, null) }
        pageIndex = pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: pageIndex.coerceAtMost((pages.size - 1).coerceAtLeast(0))
        showPage()
    }

    private fun current() = pages.getOrNull(pageIndex)
    private fun page(delta: Int) { pageIndex = (pageIndex + delta).coerceIn(0, (pages.size - 1).coerceAtLeast(0)); showPage() }
    private fun showPage() {
        val page = current()
        if (page != displayed) {
            snapshot.showSnapshot(page?.draft?.text ?: page?.note?.text.orEmpty(), page?.anchor)
            displayed = page
        }
        counter.text = if (pages.isEmpty()) "暂无注释" else "${pageIndex + 1}/${pages.size}"
        previous.isEnabled = pageIndex > 0
        next.isEnabled = pageIndex < pages.lastIndex
        addButton.isEnabled = !readOnly
        editButton.isEnabled = !readOnly && page != null
        deleteButton.isEnabled = !readOnly && page?.note != null
        error.text = state.error.orEmpty()
        error.isVisible = !state.error.isNullOrEmpty()
        revalidate(); repaint()
    }

    override fun close() {
        if (closed) return
        closed = true
        fonts.close(); snapshot.close()
    }
}
