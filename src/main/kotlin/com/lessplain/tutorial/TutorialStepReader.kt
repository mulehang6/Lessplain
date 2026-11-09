package com.lessplain.tutorial

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import com.lessplain.ide.captureEditorAnchor
import com.lessplain.ide.captureSourceFocus
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.showNativeMenu
import com.lessplain.ui.compactIconButton
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseWheelEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** 同一 Markdown 的逐点阅读和任务式编辑；批注仍使用原文偏移。 */
internal class TutorialStepReader(
    private val view: TutorialEditorView,
    reading: TutorialReadingState,
) : JPanel(BorderLayout()), AutoCloseable {
    private val service = view.service
    private val follower = TutorialSourceFollower(service.sources) { notice.text = "源码已变化，请重新绑定当前讲解点" }
    private val wheel = TutorialStepWheel()
    private val previous = compactIconButton(AllIcons.Actions.Back, "上一点") { move(-1) }
    private val next = compactIconButton(AllIcons.Actions.Forward, "下一点") { move(1) }
    private val progress = JLabel("", JLabel.CENTER)
    private val notice = JLabel()
    private val follow = JBCheckBox("跟随源码", reading.followSource)
    private val edit = compactIconButton(AllIcons.Actions.Edit, "修改当前点") { showEditMenu() }
    private var currentId = reading.stepId
    private var points = emptyList<TutorialStep>()
    private var active = false
    private var closed = false
    private var currentSource: String? = null
    val current get() = points.firstOrNull { it.id == currentId } ?: points.firstOrNull()
    val stepId get() = current?.id
    val followSource get() = follow.isSelected
    val available get() = points.isNotEmpty()

    init {
        border = JBUI.Borders.empty(4, 8)
        add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
            add(previous)
            add(progress.apply { border = JBUI.Borders.empty(0, 4) })
            add(next)
            add(follow)
            add(edit)
        }, BorderLayout.NORTH)
        add(notice, BorderLayout.SOUTH)
        follow.addActionListener { if (follow.isSelected) locate() else follower.clear() }
        prepare()
    }

    private fun showEditMenu() {
        showNativeMenu(edit, listOf(
            NativeMenuItem("修改说明") { editCurrent() },
            NativeMenuItem("绑定编辑器选中的源码", !service.store.readOnly) { rebind() },
            NativeMenuItem("向前移动", !service.store.readOnly && points.indexOf(current) > 0) { reorder(-1) },
            NativeMenuItem("向后移动", !service.store.readOnly && points.indexOf(current) < points.lastIndex) { reorder(1) },
        ))
    }

    fun prepare() {
        if (closed) return
        val oldIndex = points.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        points = tutorialSteps(view.editor.document.text)
        if (points.none { it.id == currentId }) currentId = points.getOrNull(oldIndex.coerceAtMost((points.size - 1).coerceAtLeast(0)))?.id
        if (currentSource != current?.sourceId) follower.clear()
        currentSource = current?.sourceId
        val index = points.indexOf(current)
        progress.text = if (index < 0) "尚未整理讲解点，请切换到整篇预览" else "${index + 1} / ${points.size}"
        previous.isEnabled = index > 0
        next.isEnabled = index >= 0 && index < points.lastIndex
        edit.isEnabled = index >= 0
        view.preview.sourceRange = if (view.mode == TutorialDisplayMode.STEPS) current?.range else null
    }

    fun move(delta: Int) {
        if (closed || view.mode != TutorialDisplayMode.STEPS) return
        val point = points.getOrNull(points.indexOf(current) + delta) ?: return
        currentId = point.id
        view.actions.dismiss()
        view.preview.select(0, 0)
        view.refresh()
        SwingUtilities.invokeLater {
            if (!closed) view.preview.followSource(point.start)
        }
        locate()
    }

    fun revealPosition(offset: Int) {
        points.firstOrNull { offset in it.range }?.let { currentId = it.id }
        prepare()
    }

    /** 长点先在内部滚动；一次手势最多推进一点，选择文字和横向滚动不换点。 */
    fun wheel(event: MouseWheelEvent): Boolean {
        if (view.mode != TutorialDisplayMode.STEPS || event.isShiftDown || view.preview.selectionStart != view.preview.selectionEnd) return false
        val bar = view.previewScroll.verticalScrollBar
        val boundary = if (event.preciseWheelRotation > 0) bar.value + bar.visibleAmount >= bar.maximum - 1 else bar.value <= bar.minimum
        val delta = wheel.turn(event.preciseWheelRotation, boundary, event.`when`)
        if (delta != 0) move(delta)
        return boundary
    }

    fun active(value: Boolean) {
        val entered = value && !active
        active = value
        if (!value) follower.clear()
        else if (entered) locate()
    }

    private fun locate() {
        if (!active || view.mode != TutorialDisplayMode.STEPS || !follow.isSelected) return
        notice.text = ""
        try { follower.show(current?.sourceId) }
        catch (failure: TutorialProblem) { follower.clear(); notice.text = "源码未联动：${failure.message}" }
    }

    private fun editCurrent() {
        val point = current ?: return
        view.display(TutorialDisplayMode.EDIT)
        view.editor.caretModel.moveToOffset(point.start.coerceAtMost(view.editor.document.textLength))
        view.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        view.editor.contentComponent.requestFocusInWindow()
    }

    private fun rebind() = service.attempt {
        val point = current ?: return@attempt
        val editor = FileEditorManager.getInstance(service.project).selectedTextEditor
            ?: throw TutorialProblem("SOURCE_NOT_FOUND", "selection", "请先在源码编辑器选中对应片段")
        tutorialRequire(editor.selectionModel.hasSelection(), "INVALID_RANGE", "selection", "请先在源码编辑器选中对应片段")
        val anchor = captureEditorAnchor(service.project, editor)
            ?: throw TutorialProblem("SOURCE_NOT_FOUND", "selection", "选区需要位于项目源码内")
        val focus = captureSourceFocus(editor.document.text, anchor.path, editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd, anchor.symbol)
        val source = service.sources.capture(anchor.path, focus.anchor.text, focus.start, focus.end)
        val doc = view.editor.document
        val marker = "<!-- lessplain:step ${point.id} source=${source.id} -->\n"
        WriteCommandAction.runWriteCommandAction(service.project, "绑定讲解源码", null, Runnable { doc.replaceString(point.markerStart, point.start, marker) })
        view.refresh()
        locate()
    }

    private fun reorder(delta: Int) {
        val point = current ?: return
        val other = points.getOrNull(points.indexOf(point) + delta) ?: return
        val doc = view.editor.document
        val first = if (delta < 0) other else point
        val second = if (delta < 0) point else other
        val replacement = doc.text.substring(second.markerStart, second.end).trimEnd() + "\n\n" + doc.text.substring(first.markerStart, first.end).trimEnd() + "\n\n"
        WriteCommandAction.runWriteCommandAction(service.project, "调整讲解顺序", null, Runnable { doc.replaceString(first.markerStart, second.end, replacement) })
        view.refresh()
    }

    override fun close() { closed = true; follower.close() }
}
