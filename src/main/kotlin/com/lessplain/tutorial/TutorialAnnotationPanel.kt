package com.lessplain.tutorial

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import com.lessplain.ui.PopupUi
import com.lessplain.ui.EditorComposition
import com.lessplain.ui.popupIconButton
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
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
) : JPanel(BorderLayout(JBUI.scale(PopupUi.GAP), JBUI.scale(PopupUi.GAP))), AutoCloseable {
    var currentId = initial.id
        private set
    private var snapshot = initial
    private var localDraft = initial.takeIf { it.isEmptyDraft }
    private var localRevision = service.store.record(initial.materialId).revision
    private var pageIndex = 0
    private var updating = false
    private var closed = false
    private var materialGeneration = 0L
    private var materialRead: AutoCloseable? = null
    private val graph = service.project.getService(com.lessplain.ide.UnderstandingService::class.java)
    private val referenceRead = com.lessplain.core.LatestBackgroundRead<Pair<String, Result<Boolean>>>(graph::readBackground) { result ->
        if (!closed) result.onSuccess { (id, availability) ->
            if (id == snapshot.answerMaterialId) availability.onSuccess { exists ->
                materialLink.text = if (exists) "查看关联教程或图" else "记录已删除"
                materialLink.isEnabled = exists
                materialLink.toolTipText = null
            }.onFailure {
                materialLink.text = "关联记录无法读取"
                materialLink.isEnabled = false
                materialLink.toolTipText = it.message
            }
        }
    }
    private val graphChanges = graph.onChanged {
        snapshot.answerMaterialId?.takeUnless { it.startsWith("tut_") }?.let(::refreshMaterialReference)
    }
    val input = JTextArea().apply { lineWrap = true; wrapStyleWord = true }
    val answer = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val quote = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 2 }
    val counter = JLabel()
    val composition = EditorComposition()
    val submit = popupIconButton(IconLoader.getIcon("/icons/send.svg", TutorialAnnotationPanel::class.java), "提交并复制问题") { requestSubmit() }
    private val inputScroll = JBScrollPane(input)
    private val answerScroll = JBScrollPane(answer)
    private val quoteScroll = JBScrollPane(quote)
    val split = OnePixelSplitter(true, 0.5f).apply { setHonorComponentsMinimumSize(false); firstComponent = inputScroll }
    val materialLink = ActionLink("查看关联教程或图") { openMaterial() }
    private val answerPanel = JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))).apply {
        add(answerScroll, BorderLayout.CENTER); add(materialLink, BorderLayout.SOUTH)
    }
    val controls = listOf(
        popupIconButton(AllIcons.Actions.Back, "上一条") { page(-1) },
        popupIconButton(AllIcons.Actions.Forward, "下一条") { page(1) },
        popupIconButton(AllIcons.General.Add, "新增") { addPage() },
        popupIconButton(AllIcons.Actions.GC, "删除") { service.attempt { deleteCurrent() } },
    )
    private val toolbar = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
        controls.forEachIndexed { index, button ->
            if (index > 0) add(javax.swing.Box.createHorizontalStrut(JBUI.scale(PopupUi.ICON_GAP)))
            add(button)
        }
    }
    private val footer = JPanel(BorderLayout()).apply { add(submit, BorderLayout.EAST) }
    private val answerSelection = TutorialAnswerSelection(answer) { selected -> service.attempt { followUp(selected) } }
    private val fonts = com.lessplain.ui.CanvasUiFonts(listOf(input, answer, quote, counter, submit, materialLink)) { colors() }

    init {
        preferredSize = JBUI.size(580, 420)
        border = JBUI.Borders.empty(PopupUi.PADDING)
        add(JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.WEST); add(counter, BorderLayout.EAST) }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))).apply { add(quoteScroll, BorderLayout.NORTH); add(split, BorderLayout.CENTER) }, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        input.addInputMethodListener(object : java.awt.event.InputMethodListener {
            override fun inputMethodTextChanged(event: java.awt.event.InputMethodEvent) {
                val text = event.text
                composition.changed(if (text == null) 0 else text.endIndex - text.beginIndex, event.committedCharacterCount)
            }
            override fun caretPositionChanged(event: java.awt.event.InputMethodEvent) = Unit
        })
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = save()
            override fun removeUpdate(event: DocumentEvent) = save()
            override fun changedUpdate(event: DocumentEvent) = save()
            private fun save() {
                if (updating || closed || service.store.readOnly) return
                service.attempt {
                    val creating = localDraft?.id == currentId
                    val draft = if (creating && service.store.record(snapshot.materialId).revision != localRevision) snapshot.copy(orphaned = true) else snapshot
                    val saved = service.store.saveAnnotationDraft(draft, input.text, creating)
                    if (saved == null) {
                        localDraft = draft.copy(text = "")
                        localRevision = service.store.record(snapshot.materialId).revision
                        snapshot = localDraft!!
                    } else {
                        if (localDraft?.id == saved.id) localDraft = null
                        snapshot = saved
                    }
                    service.changed(snapshot.materialId)
                    refresh(false)
                }
            }
        })
        colors(); refresh(true)
    }

    private fun pages() = service.store.annotations(snapshot.materialId)
        .filter { !it.isEmptyDraft && sameAnnotationGroup(it, snapshot) } + listOfNotNull(localDraft)
    fun matches(other: TutorialAnnotation) = !closed && sameAnnotationGroup(snapshot, other)
    fun focus(item: TutorialAnnotation) {
        if (item.isEmptyDraft) addPage() else { currentId = item.id; refresh(true) }
    }
    fun page(delta: Int) {
        val pages = pages()
        if (pages.isEmpty()) return
        currentId = pages[(pageIndex + delta).coerceIn(0, pages.lastIndex)].id
        refresh(true)
    }
    fun addPage() {
        if (service.store.readOnly) return
        val next = localDraft ?: snapshot.copy(id = newId(if (snapshot.kind == "note") "note" else "q"), text = "", answer = "", status = "draft", contextId = null, answerMaterialId = null)
            .also { localDraft = it; localRevision = service.store.record(it.materialId).revision }
        currentId = next.id
        refresh(true)
        input.requestFocusInWindow()
    }
    fun deleteCurrent() {
        if (service.store.readOnly) return
        if (localDraft?.id == currentId) localDraft = null
        else {
            val branch = service.store.annotationBranch(currentId)
            if (!confirmDelete(branch.size)) return
            service.deleteAnnotation(currentId)
        }
        refresh(false)
    }
    fun followUp(selected: String) {
        if (service.store.readOnly) return
        val item = service.store.annotation(currentId)
        open(TutorialAnnotation(newId("q"), item.materialId, "question", item.start, item.end, selected,
            parentId = item.id, orphaned = item.orphaned))
    }

    private fun requestSubmit() {
        val requested = currentId
        input.inputContext?.endComposition()
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (!closed && currentId == requested && !composition.active && input.text.isNotBlank()) service.attempt {
                service.submitQuestion(currentId)
                refresh(false)
            }
        }
    }

    private fun openMaterial() {
        val questionId = currentId
        val material = service.store.annotation(questionId).answerMaterialId ?: return
        val request = ++materialGeneration
        materialRead?.close()
        if (material.startsWith("tut_")) service.attempt { service.open(material) }
        else {
            val graph = service.project.getService(com.lessplain.ide.UnderstandingService::class.java)
            materialRead = graph.readBackground({ graph.storage.loadRecord(material) }) { result ->
                if (!closed && request == materialGeneration && currentId == questionId && snapshot.answerMaterialId == material) {
                    result.onSuccess { record -> service.attempt { com.lessplain.ide.uiRead {
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(service.project).openFile(graph.editorFile(record), true)
                    } } }.onFailure { failure -> service.attempt { throw failure } }
                }
            }
        }
    }

    /** 关联材料变化时只刷新引用入口，不替换正在输入的问题或回答区。 */
    fun refreshMaterialReference(id: String) {
        if (!closed && snapshot.answerMaterialId == id) updateMaterialLink()
    }
    private fun updateMaterialLink() {
        val id = snapshot.answerMaterialId
        val tutorial = id?.startsWith("tut_") == true
        val removed = tutorial && service.store.isUnavailable(requireNotNull(id))
        materialLink.text = if (removed) "教程已删除" else "查看关联教程或图"
        materialLink.isEnabled = tutorial && !removed
        materialLink.toolTipText = null
        if (id != null && !tutorial) referenceRead.request { id to com.lessplain.core.cancellationAwareResult { graph.repository.recordExists(id) } }
    }

    fun refresh(replaceInput: Boolean) {
        if (closed) return
        val all = service.store.annotations(snapshot.materialId)
        if (snapshot.parentId != null && all.none { it.id == snapshot.parentId }) { closeWindow(); return }
        all.firstOrNull { it.id == currentId && !it.isEmptyDraft }?.let { snapshot = it }
        val pages = pages()
        val item = pages.firstOrNull { it.id == currentId } ?: pages.getOrNull(pageIndex.coerceAtMost(pages.lastIndex))
        if (item == null) { closeWindow(); return }
        val changedPage = currentId != item.id
        currentId = item.id; snapshot = item
        updating = true
        try {
            if (replaceInput || changedPage) { answerSelection.dismiss(); input.text = item.text; input.caretPosition = input.document.length }
            input.isEditable = !service.store.readOnly && (item.kind == "note" || item.status == "draft")
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
            updateMaterialLink()
            footer.isVisible = item.kind == "question" && item.status == "draft"
            submit.isEnabled = !service.store.readOnly && input.text.isNotBlank() && item.status == "draft"
            pageIndex = pages.indexOfFirst { it.id == currentId }
            counter.text = "${pageIndex + 1}/${pages.size} · ${when (item.status) { "pending" -> "待答"; "answered" -> "已答"; else -> if (item.isEmptyDraft) "新建" else "自动保存" }}"
        } finally { updating = false }
        controls[2].isEnabled = !service.store.readOnly
        controls[3].isEnabled = !service.store.readOnly
        controls[0].isEnabled = pageIndex > 0
        controls[1].isEnabled = pageIndex < pages.lastIndex
        revalidate(); repaint()
    }
    private fun colors() {
        val background = UIUtil.getTextFieldBackground()
        listOf(input, answer).forEach { it.background = background; it.foreground = UIUtil.getLabelForeground() }
        quote.background = UIUtil.getPanelBackground(); quote.foreground = UIUtil.getLabelForeground()
        listOf(inputScroll, answerScroll, quoteScroll).forEach { it.border = JBUI.Borders.empty(); it.viewport.background = background }
    }
    override fun close() { if (!closed) { closed = true; materialGeneration++; materialRead?.close(); referenceRead.close(); graphChanges.close(); answerSelection.close(); fonts.close() } }
}
