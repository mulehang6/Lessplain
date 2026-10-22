package com.lessplain.tutorial

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.lessplain.ide.uiRead
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.installNativeMenu
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/** 编辑与只读预览明确分离，模式切换不替换规范 Document 或输入状态。 */
internal class TutorialEditorView(private val project: Project, val id: String) : JPanel(BorderLayout()), AutoCloseable, com.intellij.openapi.editor.colors.EditorColorsListener {
    val service = project.getService(TutorialService::class.java)
    val editor = EditorFactory.getInstance().createEditor(service.document(id), project)
    val preview = TutorialPreviewPane(editor, service, id)
    val previewScroll = TutorialPreviewScrollPane(preview, editor)
    private val lifetime = Disposer.newDisposable("Lessplain tutorial editor")
    private val floating = TutorialAnnotationWindows(project, id, service, this)
    val actions = TutorialSelectionActions(service, id, floating)
    private val marks = TutorialAnnotationMarks(editor, service, id)
    private val body = JPanel(BorderLayout())
    val split = OnePixelSplitter(false, 0.5f).apply { setHonorComponentsMinimumSize(false) }
    val tabActions = TutorialLayoutActions(this)
    private val initialReading = service.store.reading(id)
    var mode = initialReading.mode
        private set
    val focusTarget get() = if (mode == TutorialDisplayMode.EDIT) editor.contentComponent else preview
    private var closed = false
    private var syncing = false
    private var refreshing = false
    private val timer = Timer(120) { uiRead { refresh() } }.apply { isRepeats = false }
    private val fonts = com.lessplain.ui.CanvasUiFonts(emptyList()) { timer.restart() }
    private val subscription = service.onChanged { changed -> if (changed == id && !closed) timer.restart() }

    init {
        editor.settings.apply { isUseSoftWraps = true; additionalColumnsCount = 0; isShowIntentionBulb = false }
        (editor as? com.intellij.openapi.editor.ex.EditorEx)?.highlighter = com.intellij.openapi.editor.highlighter.EditorHighlighterFactory.getInstance().createEditorHighlighter(project, "tutorial.md")
        editor.putUserData(tutorialActionsKey, actions)
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(lifetime)
            .subscribe(com.intellij.openapi.editor.colors.EditorColorsManager.TOPIC, this)
        add(body, BorderLayout.CENTER)
        editor.caretModel.moveToOffset(initialReading.caret.coerceIn(0, editor.document.textLength))
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { actions.dismiss(); timer.restart() }
        }, lifetime)
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(event: SelectionEvent) = sourceSelection()
        }, lifetime)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                if (SwingUtilities.isRightMouseButton(event.mouseEvent)) { sourceSelection(); actions.suspendForMenu() } else actions.beginSelection()
            }
            override fun mouseReleased(event: EditorMouseEvent) {
                if (!SwingUtilities.isRightMouseButton(event.mouseEvent)) { sourceSelection(); actions.endSelection() }
            }
        }, lifetime)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) { actions.hover(editor.contentComponent, event.offset, event.mouseEvent.point.apply { translate(8, editor.lineHeight) }) }
        }, lifetime)
        preview.addCaretListener {
            val p = preview.modelToView2D(preview.caretPosition)?.bounds?.let { Point(it.x, it.y + it.height + 4) } ?: Point()
            actions.changed(preview, preview.selectedSource(), p)
        }
        preview.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(event)) { actions.changed(preview, preview.selectedSource(), event.point); actions.suspendForMenu() }
                else actions.beginSelection()
            }
            override fun mouseReleased(event: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(event)) { actions.endSelection(); preview.openLink(event.point) }
            }
        })
        preview.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) { actions.hover(preview, preview.sourceAt(event.point), Point(event.x + 8, event.y + 20)) }
        })
        editor.contentComponent.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(event: java.awt.event.FocusEvent) = sourceSelection()
        })
        preview.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(event: java.awt.event.FocusEvent) {
                actions.changed(preview, preview.selectedSource(), preview.modelToView2D(preview.caretPosition)?.bounds?.location ?: Point())
            }
        })
        installNativeMenu(preview) {
            actions.suspendForMenu()
            listOf(NativeMenuItem("复制", preview.selectedText != null) { preview.copy() },
                NativeMenuItem("全选") { preview.selectAll() },
                NativeMenuItem("写笔记", preview.selectedSource() != null) { actions.create("note") },
                NativeMenuItem("追问此处", preview.selectedSource() != null) { actions.create("question") }) + navigationItems(preview)
        }
        val popupKey = object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) { if (event.keyCode == KeyEvent.VK_CONTEXT_MENU || event.keyCode == KeyEvent.VK_F10 && event.isShiftDown) actions.suspendForMenu() }
        }
        preview.addKeyListener(popupKey); editor.contentComponent.addKeyListener(popupKey)
        editor.scrollingModel.addVisibleAreaListener(object : VisibleAreaListener {
            override fun visibleAreaChanged(event: VisibleAreaEvent) = syncFromEditor()
        }, lifetime)
        previewScroll.viewport.addChangeListener { syncFromPreview() }
        (editor as? com.intellij.openapi.editor.ex.EditorEx)?.addPropertyChangeListener(java.beans.PropertyChangeListener { timer.restart() }, lifetime)
        refresh()
        display(mode, false)
        SwingUtilities.invokeLater {
            if (!closed) uiRead {
                syncing = true
                try { editor.scrollingModel.scrollVertically(initialReading.scroll); preview.followSource(initialReading.previewSource) }
                finally { syncing = false }
            }
        }
    }

    fun navigationItems(anchor: javax.swing.JComponent) = listOf(
        NativeMenuItem("批注索引") { actions.index(anchor) },
        NativeMenuItem("关联源码", service.store.record(id).sourceIds.isNotEmpty()) {
            actions.sources(anchor)
        },
    )

    private fun sourceSelection() {
        if (closed) return
        uiRead {
            val selection = editor.selectionModel
            val point = editor.offsetToXY(selection.selectionEnd).apply { translate(8, editor.lineHeight + 4) }
            actions.changed(editor.contentComponent, if (selection.hasSelection()) selection.selectionStart until selection.selectionEnd else null, point)
        }
    }
    private fun editorSource(): Int = editor.logicalPositionToOffset(editor.xyToLogicalPosition(Point(0, editor.scrollingModel.verticalScrollOffset)))
    private fun syncFromEditor() {
        if (closed || mode != TutorialDisplayMode.SPLIT || syncing || refreshing) return
        uiRead {
            syncing = true
            try { preview.followSource(editorSource()) } finally { syncing = false }
        }
    }
    private fun syncFromPreview() {
        if (closed || mode != TutorialDisplayMode.SPLIT || syncing || refreshing) return
        uiRead {
            syncing = true
            try { scrollEditorTo(preview.topSource()) } finally { syncing = false }
        }
    }
    private fun scrollEditorTo(source: Int) {
        editor.scrollingModel.disableAnimation()
        try { editor.scrollingModel.scrollVertically(editor.offsetToXY(source.coerceIn(0, editor.document.textLength)).y) }
        finally { editor.scrollingModel.enableAnimation() }
    }
    fun display(next: TutorialDisplayMode, persistCurrent: Boolean = true) {
        val source = if (mode == TutorialDisplayMode.EDIT) editorSource() else preview.topSource()
        if (persistCurrent) saveReading()
        actions.dismiss(); syncing = true
        try {
            mode = next
            body.removeAll(); split.firstComponent = null; split.secondComponent = null
            when (next) {
                TutorialDisplayMode.EDIT -> body.add(editor.component)
                TutorialDisplayMode.PREVIEW -> body.add(previewScroll)
                TutorialDisplayMode.SPLIT -> { split.firstComponent = editor.component; split.secondComponent = previewScroll; body.add(split) }
            }
            body.revalidate(); body.repaint()
            SwingUtilities.invokeLater {
                if (!closed) uiRead {
                    syncing = true
                    try {
                        if (next == TutorialDisplayMode.SPLIT) split.proportion = service.store.reading(id).divider.coerceIn(0.15, 0.85).toFloat()
                        scrollEditorTo(source)
                        preview.followSource(source)
                    } finally { syncing = false }
                }
            }
        } finally { syncing = false }
    }
    fun refresh() {
        if (closed) return
        val source = preview.topSource()
        refreshing = true
        try { preview.refresh(); marks.refresh(); service.takeAnnotationDestination(id)?.let(floating::open) }
        finally { refreshing = false }
        preview.followSource(source)
        toolTipText = service.store.writeFailure?.let { "保存失败：${it.message}" }
    }
    private fun saveReading() {
        val reading = service.store.reading(id)
        service.store.saveReading(id, reading.copy(caret = editor.caretModel.offset, scroll = editor.scrollingModel.verticalScrollOffset,
            mode = mode, previewSource = preview.topSource(), divider = if (mode == TutorialDisplayMode.SPLIT) split.proportion.toDouble() else reading.divider))
    }
    fun active(value: Boolean) { floating.active(value); actions.active(value); if (value) timer.restart() }
    override fun globalSchemeChange(scheme: com.intellij.openapi.editor.colors.EditorColorsScheme?) { timer.restart() }
    override fun close() {
        if (closed) return
        closed = true; timer.stop(); subscription.close(); fonts.close()
        preview.close(); saveReading(); actions.close(); floating.close(); marks.close()
        editor.putUserData(tutorialActionsKey, null)
        Disposer.dispose(lifetime); EditorFactory.getInstance().releaseEditor(editor)
    }
}
