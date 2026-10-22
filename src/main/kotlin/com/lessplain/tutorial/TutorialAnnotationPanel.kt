package com.lessplain.tutorial

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** 批注内容与分页状态独立于浮窗，异步答复只更新回答区，不替换正在输入的内容。 */
internal class TutorialAnnotationPanel(
    private val service: TutorialService,
    initial: TutorialAnnotation,
    private val open: (TutorialAnnotation) -> Unit,
    private val closeWindow: () -> Unit,
    private val confirmDelete: (Int) -> Boolean = { count ->
        val message = if (initial.kind == "note") "删除这条笔记？" else "删除这条追问、回答及其 ${count - 1} 条后续追问？"
        Messages.showYesNoDialog(service.project, message, "删除批注", "删除", "取消", Messages.getQuestionIcon()) == Messages.YES
    },
) : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))), AutoCloseable {
    var currentId = initial.id
        private set
    private var snapshot = initial
    private var pageIndex = 0
    private var updating = false
    private var closed = false
    val input = JTextArea().apply { lineWrap = true; wrapStyleWord = true }
    val answer = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val quote = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 2 }
    val counter = JLabel()
    val submit = JButton("提交并复制问题")
    private val inputScroll = JBScrollPane(input)
    private val answerScroll = JBScrollPane(answer)
    private val quoteScroll = JBScrollPane(quote)
    val split = OnePixelSplitter(true, 0.5f).apply { setHonorComponentsMinimumSize(false); firstComponent = inputScroll }
    val materialLink = ActionLink("查看关联教程或图") { openMaterial() }
    private val answerPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(answerScroll, BorderLayout.CENTER); add(materialLink, BorderLayout.SOUTH)
    }
    val controls: List<AnAction> = listOf(
        control("上一条", AllIcons.Actions.Back, { pageIndex > 0 }) { page(-1) },
        control("下一条", AllIcons.Actions.Forward, { pageIndex < pages().lastIndex }) { page(1) },
        control("新增", AllIcons.General.Add) { addPage() },
        control("删除", AllIcons.Actions.GC) { deleteCurrent() },
    )
    private val toolbar = ActionManager.getInstance().createActionToolbar("Lessplain.Tutorial.Annotation", DefaultActionGroup(*controls.toTypedArray()), true).apply {
        setTargetComponent(this@TutorialAnnotationPanel)
    }
    private val footer = JPanel(BorderLayout()).apply { add(submit, BorderLayout.WEST) }
    private val answerSelection = TutorialAnswerSelection(answer) { selected -> service.attempt { followUp(selected) } }
    private val fonts = com.lessplain.ui.CanvasUiFonts(listOf(input, answer, quote, counter, submit, materialLink)) { colors() }

    init {
        preferredSize = JBUI.size(580, 420)
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.WEST); add(counter, BorderLayout.EAST) }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, JBUI.scale(8))).apply { add(quoteScroll, BorderLayout.NORTH); add(split, BorderLayout.CENTER) }, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        submit.addActionListener { service.attempt { service.submitQuestion(currentId); refresh(false) } }
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = save()
            override fun removeUpdate(event: DocumentEvent) = save()
            override fun changedUpdate(event: DocumentEvent) = save()
            private fun save() {
                if (updating || closed) return
                if (service.store.annotations(snapshot.materialId).none { it.id == currentId }) { closeWindow(); return }
                service.store.updateAnnotation(currentId) { it.copy(text = input.text) }
                service.changed(snapshot.materialId)
            }
        })
        colors(); refresh(true)
    }

    private fun control(title: String, icon: Icon, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
        object : DumbAwareAction(title, title, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) { event.presentation.isEnabled = !closed && enabled() }
            override fun actionPerformed(event: AnActionEvent) { service.attempt(perform) }
        }

    private fun pages() = service.store.annotations(snapshot.materialId).filter { sameAnnotationGroup(it, snapshot) }
    fun matches(other: TutorialAnnotation) = !closed && sameAnnotationGroup(snapshot, other)
    fun focus(id: String) { currentId = id; refresh(true) }
    fun page(delta: Int) {
        val pages = pages()
        if (pages.isEmpty()) return
        currentId = pages[(pageIndex + delta).coerceIn(0, pages.lastIndex)].id
        refresh(true)
    }
    fun addPage() {
        val item = service.store.annotation(currentId)
        val next = service.store.createAnnotation(item.copy(id = newId(if (item.kind == "note") "note" else "q"), text = "", answer = "", status = "draft", contextId = null, answerMaterialId = null))
        focus(next.id); service.changed(item.materialId)
    }
    fun deleteCurrent() {
        val branch = service.store.annotationBranch(currentId)
        if (!confirmDelete(branch.size)) return
        service.deleteAnnotation(currentId)
        refresh(false)
    }
    fun followUp(selected: String) {
        val item = service.store.annotation(currentId)
        val child = service.store.createAnnotation(TutorialAnnotation(newId("q"), item.materialId, "question", item.start, item.end, selected,
            parentId = item.id, orphaned = item.orphaned))
        open(child); service.changed(item.materialId)
    }
    private fun openMaterial() {
        val material = service.store.annotation(currentId).answerMaterialId ?: return
        service.attempt {
            if (material.startsWith("tut_")) service.open(material)
            else {
                val graph = service.project.getService(com.lessplain.ide.UnderstandingService::class.java)
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(service.project).openFile(graph.editorFile(graph.repository.getRecord(material)), true)
            }
        }
    }

    fun refresh(replaceInput: Boolean) {
        if (closed) return
        val all = service.store.annotations(snapshot.materialId)
        val found = all.firstOrNull { it.id == currentId }
        val item = found ?: pages().let { it.getOrNull(pageIndex.coerceAtMost(it.lastIndex)) }
        if (item == null) { closeWindow(); return }
        val changedPage = currentId != item.id
        currentId = item.id; snapshot = item
        updating = true
        try {
            if (replaceInput || changedPage) { answerSelection.dismiss(); input.text = item.text; input.caretPosition = input.document.length }
            input.isEditable = item.kind == "note" || item.status == "draft"
            if (answer.text != item.answer) {
                val caret = answer.caretPosition
                answerSelection.dismiss(); answer.text = item.answer
                answer.caretPosition = caret.coerceAtMost(answer.document.length)
            }
            quote.text = (if (item.orphaned) "原文已删除，保留快照：\n" else "原文：\n") + item.quote
            val showAnswer = item.kind == "question" && (item.answer.isNotEmpty() || item.answerMaterialId != null)
            if (showAnswer && split.secondComponent == null) split.secondComponent = answerPanel
            else if (!showAnswer && split.secondComponent != null) split.secondComponent = null
            materialLink.isVisible = item.answerMaterialId != null
            footer.isVisible = item.kind == "question" && item.status == "draft"
            val pages = pages()
            pageIndex = pages.indexOfFirst { it.id == currentId }
            counter.text = "${pageIndex + 1}/${pages.size} · ${when (item.status) { "pending" -> "待答"; "answered" -> "已答"; else -> "自动保存" }}"
        } finally { updating = false }
        toolbar.updateActionsAsync()
        revalidate(); repaint()
    }
    private fun colors() {
        val background = UIUtil.getTextFieldBackground()
        listOf(input, answer).forEach { it.background = background; it.foreground = UIUtil.getLabelForeground() }
        quote.background = UIUtil.getPanelBackground(); quote.foreground = UIUtil.getLabelForeground()
        listOf(inputScroll, answerScroll, quoteScroll).forEach { it.border = JBUI.Borders.empty(); it.viewport.background = background }
    }
    override fun close() { if (!closed) { closed = true; answerSelection.close(); fonts.close() } }
}
