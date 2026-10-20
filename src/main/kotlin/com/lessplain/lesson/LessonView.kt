package com.lessplain.lesson

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.lessplain.storage.sha256
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private data class LessonDraft(val target: LessonTarget, val question: Boolean, var text: String = "")

/** 完整教材自由阅读；补充和输入框位于目标块之后，不改动真实项目的编辑器。 */
internal class LessonView(private val project: Project, private val lessonId: String) : JPanel(BorderLayout()), AutoCloseable {
    private val service = project.getService(LessonService::class.java)
    private val body = LessonPagePanel().apply {
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
    }
    private val scroll = JScrollPane(body).apply { border = JBUI.Borders.empty() }
    private val resources = mutableListOf<AutoCloseable>()
    private val anchors = linkedMapOf<String, JComponent>()
    private val noteEdits = mutableMapOf<String, String>()
    private val noteEditors = mutableMapOf<String, JTextArea>()
    private val expanded = mutableSetOf<String>()
    private var draft: LessonDraft? = null
    private var draftEditor: JTextArea? = null
    private var signature = ""
    private var closed = false
    private var rendering = false
    private var refreshPending = false
    private var restored = false
    private var reflowPosition: LessonBrowse? = null
    private val noteTimer = Timer(400) { flushNotes() }.apply { isRepeats = false }
    private val browseTimer = Timer(300) { if (!rendering) persistBrowse() }.apply { isRepeats = false }
    private val subscription = service.onChanged { refresh() }

    init {
        add(scroll, BorderLayout.CENTER)
        scroll.verticalScrollBar.addAdjustmentListener { if (!rendering && !closed) browseTimer.restart() }
        service.background({ service.workspace.lesson(lessonId) }) { refresh(force = true) }
    }

    private fun record(): LessonRecord? = service.snapshot.lessons.firstOrNull { it.id == lessonId }
    private fun annotations(): List<LessonAnnotation> = service.snapshot.annotations.filter { it.lessonId == lessonId }

    private fun refresh(force: Boolean = false) = lessonUiRead { render(force) }

    private fun render(force: Boolean) {
        if (closed) return
        val lesson = record() ?: return
        val notes = annotations()
        val destination = service.takeDestination(lessonId)
        if (!force && destination == null && (draftEditor?.isFocusOwner == true || noteEditors.values.any { it.isFocusOwner })) {
            refreshPending = true
            return
        }
        refreshPending = false
        destination?.let {
            var id = it.annotationId ?: it.owner
            val visited = mutableSetOf<String>()
            while (id != null && visited.add(id)) {
                expanded.add(id)
                id = notes.firstOrNull { note -> note.id == id }?.target?.annotationId
            }
        }
        val next = "${lesson.revision}|${lesson.blocks}|${lesson.examples}|${notes.map { Triple(it.id, it.status, it.answer) }}"
        if (!force && signature == next && destination == null) return
        val position = destination?.let { LessonBrowse(it.annotationId?.let { id -> "note:$id" }
            ?: it.blockId?.let { id -> blockKey(id, it.owner) } ?: "title") }
            ?: if (restored) readingPosition() else service.snapshot.browse[lessonId] ?: LessonBrowse()
        if (!restored) expanded += position.expandedAnnotations
        val retainInputFocus = draftEditor?.isFocusOwner == true
        val inputSelection = draftEditor?.let { it.selectionStart to it.selectionEnd }
        val focusedNote = noteEditors.entries.firstOrNull { it.value.isFocusOwner }?.let { (id, editor) ->
            Triple(id, editor.selectionStart, editor.selectionEnd)
        }
        signature = next
        rendering = true
        resources.forEach(AutoCloseable::close)
        resources.clear(); anchors.clear(); noteEditors.clear(); body.removeAll()
        addBody(selectableLessonText(lesson.title, 1), "title")
        val origin = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JButton("返回源码").apply { addActionListener { service.navigateSource(lesson.origin.anchor, lesson.origin.focus) } })
            add(JButton("我的笔记").apply { addActionListener { compose(sourceTarget(lesson), false) } })
            add(JButton("学习材料").apply { addActionListener { showLessonLibrary(project, anchor = this) } })
            if (lesson.origin.recordId != null) add(JButton("来源理解图").apply {
                addActionListener {
                    val graphService = project.getService(com.lessplain.ide.UnderstandingService::class.java)
                    graphService.readBackground({ graphService.storage.loadRecord(lesson.origin.recordId) }) { result ->
                        result.onSuccess { com.lessplain.diagram.openRecordInEditor(project, it) }
                    }
                }
            })
        }
        addBody(origin)
        service.sourceStatus(lesson)?.let { addBody(selectableLessonText(it)) }
        if (lesson.blocks.isEmpty()) {
            addBody(selectableLessonText("学习问题已准备好。交给当前 Agent 后，教材会保存在这个位置。"))
            service.snapshot.contexts.firstOrNull { it.lessonId == lessonId && it.annotationId == null }?.let { context ->
                addBody(JButton("复制学习问题").apply { addActionListener { service.copyQuestion(context.token) } })
            }
        }
        appendComposer(null, null)
        appendAnnotations(null, null)
        val blockViews = LessonBlockViews(project, service, lesson, resources, ::compose, ::preserveReflow)
        lesson.blocks.forEach { appendBlock(blockViews, it, null) }
        body.revalidate(); body.repaint()
        SwingUtilities.invokeLater {
            if (closed) return@invokeLater
            restorePosition(position)
            rendering = false
            restored = true
            if (retainInputFocus) draftEditor?.let { editor ->
                editor.requestFocusInWindow()
                inputSelection?.let { editor.select(it.first.coerceAtMost(editor.text.length), it.second.coerceAtMost(editor.text.length)) }
            }
            focusedNote?.let { (id, start, end) -> noteEditors[id]?.let { editor ->
                editor.requestFocusInWindow()
                editor.select(start.coerceAtMost(editor.text.length), end.coerceAtMost(editor.text.length))
            } }
        }
    }

    private fun appendBlock(views: LessonBlockViews, block: LessonBlock, owner: String?) {
        addBody(views.create(block, owner), blockKey(block.id, owner))
        appendComposer(block.id, owner)
        appendAnnotations(block.id, owner)
    }

    private fun appendAnnotations(blockId: String?, owner: String?) {
        val lesson = record() ?: return
        annotations().filter { it.target.blockId == blockId && it.target.annotationId == owner }.forEach { annotation ->
            val current = service.engine.targetIsCurrent(lesson, annotation.target)
            val title = annotation.target.selectedText.replace(Regex("\\s+"), " ").take(48)
            val heading = JButton("${if (annotation.id in expanded) "▾" else "▸"} 关于「$title」" +
                if (!current) " · 内容已变化" else "").apply {
                horizontalAlignment = javax.swing.SwingConstants.LEFT
                isContentAreaFilled = false
                border = JBUI.Borders.empty(6, 0)
                addActionListener {
                    if (!expanded.add(annotation.id)) expanded.remove(annotation.id)
                    refresh(force = true)
                    persistBrowse()
                }
            }
            addBody(heading, "note:${annotation.id}")
            if (annotation.id !in expanded) return@forEach
            if (annotation.question.isNotBlank()) addBody(selectableLessonText(annotation.question))
            if (annotation.status == "pending") addBody(JButton("问题已复制 · 再次复制给 Agent").apply {
                addActionListener { annotation.contextToken?.let(service::copyQuestion) }
            })
            val views = LessonBlockViews(project, service, lesson, resources, ::compose, ::preserveReflow)
            annotation.answer.forEach { appendBlock(views, it, annotation.id) }
            addBody(JLabel("我的理解"))
            val note = JTextArea(noteEdits[annotation.id] ?: annotation.note, 3, 30).apply {
                refreshWhenInputEnds(this)
                lineWrap = true; wrapStyleWord = true
                border = JBUI.Borders.empty(6)
                accessibleContext.accessibleName = "关于 $title 的个人笔记"
                document.addDocumentListener(textChanged {
                    noteEdits[annotation.id] = text
                    service.stageNote(annotation.id, text)
                    noteTimer.restart()
                })
            }
            noteEditors[annotation.id] = note
            addBody(contentScroll(note, 160))
        }
    }

    private fun compose(target: LessonTarget, question: Boolean) {
        draft = LessonDraft(target, question)
        refresh(force = true)
        SwingUtilities.invokeLater {
            draftEditor?.let { it.scrollRectToVisible(java.awt.Rectangle(0, 0, it.width, it.height)); it.requestFocusInWindow() }
        }
    }

    private fun appendComposer(blockId: String?, owner: String?) {
        val current = draft?.takeIf { it.target.blockId == blockId && it.target.annotationId == owner } ?: return
        addBody(selectableLessonText("${if (current.question) "追问" else "笔记"} · ${current.target.selectedText.take(120)}"))
        val input = JTextArea(current.text, 4, 30).apply {
            refreshWhenInputEnds(this)
            lineWrap = true; wrapStyleWord = true
            border = JBUI.Borders.empty(8)
            accessibleContext.accessibleName = if (current.question) "在此处提问" else "在此处记录理解"
            document.addDocumentListener(textChanged { current.text = text })
        }
        draftEditor = input
        addBody(contentScroll(input, 180), "composer")
        addBody(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JButton(if (current.question) "保存并复制问题" else "保存笔记").apply {
                addActionListener {
                    if (current.text.isBlank()) return@addActionListener
                    isEnabled = false
                    service.annotate(lessonId, current.target,
                        if (current.question) current.text else "", if (current.question) "" else current.text,
                        failed = { isEnabled = true }) { annotation ->
                        if (draft === current) { draft = null; draftEditor = null }
                        expanded += annotation.id
                    }
                }
            })
            add(JButton("取消").apply { addActionListener { draft = null; draftEditor = null; refresh(force = true) } })
        })
    }

    private fun sourceTarget(lesson: LessonRecord): LessonTarget = lesson.origin.anchor.text.let {
        LessonTarget(start = 0, end = it.length, selectedText = it, contentVersion = sha256(it))
    }

    private fun addBody(component: JComponent, key: String? = null) {
        component.alignmentX = Component.LEFT_ALIGNMENT
        if (component !is javax.swing.JTextPane && component !is JScrollPane) {
            component.maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height)
        }
        body.add(component)
        body.add(Box.createVerticalStrut(JBUI.scale(12)))
        if (key != null) anchors[key] = component
    }

    private fun readingPosition(): LessonBrowse {
        val y = scroll.verticalScrollBar.value
        val entry = anchors.entries.lastOrNull { (_, component) -> component.y <= y }
        return LessonBrowse(entry?.key, y - (entry?.value?.y ?: 0), expanded.toSet())
    }

    private fun preserveReflow(change: () -> Unit) {
        if (closed) return
        val position = reflowPosition ?: readingPosition().also { reflowPosition = it }
        change()
        body.revalidate()
        SwingUtilities.invokeLater {
            if (!closed) restorePosition(position)
            reflowPosition = null
        }
    }

    fun activated() { refresh(force = true) }

    private fun restorePosition(position: LessonBrowse) {
        val y = position.blockKey?.let { anchors[it]?.y } ?: 0
        scroll.verticalScrollBar.value = (y + position.offset).coerceAtLeast(0)
    }

    private fun persistBrowse() { if (!closed && restored) service.saveBrowse(lessonId, readingPosition()) }
    private fun flushNotes() { noteEdits.forEach { (id, text) -> service.saveNote(id, text) } }

    /** 补充回答不能重建正在输入的组件，避免打断选区或输入法组合。 */
    private fun refreshWhenInputEnds(input: JTextArea) {
        input.addFocusListener(object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) {
                SwingUtilities.invokeLater { if (refreshPending && !closed) refresh() }
            }
        })
    }

    override fun close() {
        if (closed) return
        persistBrowse(); flushNotes()
        closed = true
        noteTimer.stop(); browseTimer.stop(); subscription.close()
        resources.forEach(AutoCloseable::close); resources.clear()
    }
}

internal fun blockKey(id: String, owner: String?) = "${owner ?: "root"}/$id"

internal fun textChanged(action: () -> Unit): DocumentListener = object : DocumentListener {
    override fun insertUpdate(event: DocumentEvent) = action()
    override fun removeUpdate(event: DocumentEvent) = action()
    override fun changedUpdate(event: DocumentEvent) = action()
}
