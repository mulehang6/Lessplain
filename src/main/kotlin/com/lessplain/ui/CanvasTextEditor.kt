package com.lessplain.ui

import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import javax.swing.text.View
import kotlin.math.ceil
import java.util.concurrent.CancellationException

internal typealias CanvasSaveDispatch<T> = (() -> T, (Result<T>) -> Unit) -> AutoCloseable

/** 草稿不依赖界面工具包；关闭保留，明确取消才丢弃。 */
internal class CanvasTextDraft(initial: String) {
    var text = initial
    var error: String? = null
    var retain = true

    fun submit(save: (String) -> Unit): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            error = "内容不能为空"
            return false
        }
        return try {
            save(normalized)
            retain = false
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "保存失败，请刷新后重试"
            false
        }
    }
}

/** 分别跟踪输入法预编辑和已提交文字，不消费输入法事件。 */
internal class EditorComposition {
    var active = false
        private set

    fun changed(total: Int, committed: Int) {
        active = total > committed
    }
}

/** 原生文字组件保留 IDE 输入法链路，不经过 Compose 事件桥。 */
internal class CanvasTextEditor(
    title: String,
    private val draft: CanvasTextDraft,
    private val multiline: Boolean,
    private val save: (String) -> Unit,
    private val saved: () -> Unit,
    private val dismiss: () -> Unit,
    private val cancel: () -> Unit,
    private val onMetricsChanged: () -> Unit = {},
    selectionPreview: SelectedCodePreview? = null,
    private val saveDispatch: CanvasSaveDispatch<Unit>? = null,
    private var editable: Boolean = true,
    private val delete: (() -> Unit)? = null,
    private val deleted: () -> Unit = {},
    cancelLabel: String? = null,
) : JPanel(BorderLayout(0, JBUI.scale(UnderstandingUiMetrics.SECTION_GAP))), AutoCloseable, UISettingsListener {
    internal val input: JTextComponent = if (multiline) {
        JBTextArea(draft.text).apply {
            lineWrap = true
            wrapStyleWord = true
        }
    } else {
        JBTextField(draft.text)
    }
    internal val composition = EditorComposition()
    internal var visibleRowCount = 1
        private set
    internal var scrollRequired = false
        private set
    internal val saveButton = JButton("保存").apply {
        isVisible = editable
        addActionListener { requestSave() }
    }
    internal val cancelButton = JButton(cancelLabel ?: if (editable) "取消" else "关闭").apply { addActionListener { cancel() } }
    internal val deleteButton = JButton("删除").apply {
        isVisible = editable && delete != null
        toolTipText = "删除此问题"
        addActionListener { requestDelete() }
    }

    internal val error = JBTextArea().apply {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(0)
    }
    private val scrollPane = if (multiline) {
        JBScrollPane(input).apply {
            verticalScrollBarPolicy = VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_NEVER
        }
    } else {
        null
    }
    private val preview = selectionPreview?.let { SelectedCodePreviewPanel(it, ::scheduleMetricsChanged) }
    private val bottom = JPanel(BorderLayout()).apply {
        add(error, BorderLayout.NORTH)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(UnderstandingUiMetrics.GAP), 0)).apply {
            add(saveButton)
            add(cancelButton)
            add(deleteButton)
        }, BorderLayout.SOUTH)
    }
    private val fonts = CanvasUiFonts(
        listOf(input, error, saveButton, cancelButton, deleteButton),
        onRefreshed = ::scheduleMetricsChanged,
    )
    private var saveQueued = false
    private var metricsQueued = false
    private var disposed = false
    private var saveTask: AutoCloseable? = null
    private var saving = false
    private val documentListener = object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateDraftAndMetrics()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateDraftAndMetrics()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateDraftAndMetrics()
    }
    private val inputListener = object : InputMethodListener {
        override fun inputMethodTextChanged(e: InputMethodEvent) {
            val text = e.text
            composition.changed(if (text == null) 0 else text.endIndex - text.beginIndex, e.committedCharacterCount)
        }

        override fun caretPositionChanged(e: InputMethodEvent) = Unit
    }

    init {
        border = JBUI.Borders.empty(UnderstandingUiMetrics.DETAIL_PADDING)
        input.enableInputMethods(true)
        input.isEditable = editable
        input.accessibleContext.accessibleName = title
        input.document.addDocumentListener(documentListener)
        input.addInputMethodListener(inputListener)
        add(scrollPane ?: input, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        preview?.let { add(it, BorderLayout.NORTH) }
        bind("control ENTER", "save") { if (!composition.active) requestSave() }
        bind("ESCAPE", "dismiss") { if (!composition.active) dismissSafely() }
        if (editable && !multiline) (input as JBTextField).addActionListener { if (!composition.active) requestSave() }
    }

    override fun uiSettingsChanged(uiSettings: UISettings) {
        fonts.refresh()
    }

    /** 测量 Swing 折行行数，使输入区显示一至八行。 */
    internal fun preferredSizeFor(width: Int, availableHeight: Int): Dimension {
        val editorWidth = width.coerceAtLeast(1)
        val maxHeight = availableHeight.coerceAtLeast(1)
        val panelInsets = insets
        updateErrorHeight(editorWidth, panelInsets)
        val contentWidth = (editorWidth - panelInsets.left - panelInsets.right).coerceAtLeast(1)
        val gap = JBUI.scale(UnderstandingUiMetrics.SECTION_GAP)
        if (!multiline) {
            visibleRowCount = 1
            scrollRequired = false
            val baseHeight = panelInsets.top + panelInsets.bottom + input.preferredSize.height +
                bottom.preferredSize.height + gap
            val previewHeight = previewHeightFor(contentWidth, maxHeight - baseHeight, gap)
            val height = baseHeight + previewHeight
            val size = Dimension(editorWidth, minOf(height, maxHeight))
            preferredSize = size
            return size
        }

        val area = input as JBTextArea
        val pane = checkNotNull(scrollPane)
        val paneInsets = pane.insets
        val textInsets = area.insets
        val lineHeight = area.getFontMetrics(area.font).height.coerceAtLeast(1)
        val scrollbarWidth = pane.verticalScrollBar.preferredSize.width.coerceAtLeast(JBUI.scale(12))
        val baseTextWidth = (editorWidth - panelInsets.left - panelInsets.right - paneInsets.left - paneInsets.right -
            textInsets.left - textInsets.right).coerceAtLeast(1)
        val baseChromeHeight = panelInsets.top + panelInsets.bottom + bottom.preferredSize.height + gap
        val inputInsets = paneInsets.top + paneInsets.bottom + textInsets.top + textInsets.bottom
        val desiredInputHeight = minOf(MAX_VISIBLE_ROWS, wrappedRows(area, baseTextWidth)) * lineHeight + inputInsets
        val inputReserve = minOf(desiredInputHeight,
            maxOf(lineHeight + inputInsets, (maxHeight - baseChromeHeight) / 3))
        val previewHeight = previewHeightFor(contentWidth, maxHeight - baseChromeHeight - inputReserve, gap)
        val chromeHeight = baseChromeHeight + previewHeight
        val maxRowsByScreen = ((maxHeight - chromeHeight - paneInsets.top - paneInsets.bottom -
            textInsets.top - textInsets.bottom) / lineHeight).coerceAtLeast(1)
        val visibleRowLimit = minOf(MAX_VISIBLE_ROWS, maxRowsByScreen)

        var scrollbarVisible = false
        var measuredRows = 1
        repeat(2) {
            val textWidth = (baseTextWidth - if (scrollbarVisible) scrollbarWidth else 0).coerceAtLeast(1)
            measuredRows = wrappedRows(area, textWidth)
            val shouldShowScrollbar = measuredRows > visibleRowLimit
            if (shouldShowScrollbar == scrollbarVisible) return@repeat
            scrollbarVisible = shouldShowScrollbar
        }
        if (scrollbarVisible) {
            val textWidth = (baseTextWidth - scrollbarWidth).coerceAtLeast(1)
            measuredRows = wrappedRows(area, textWidth)
        }

        val visibleRows = minOf(measuredRows, visibleRowLimit).coerceAtLeast(1)
        visibleRowCount = visibleRows
        scrollRequired = measuredRows > visibleRows
        val desiredPaneHeight = visibleRows * lineHeight + textInsets.top + textInsets.bottom + paneInsets.top + paneInsets.bottom
        val paneHeight = desiredPaneHeight.coerceAtMost((maxHeight - chromeHeight).coerceAtLeast(1))
        pane.preferredSize = Dimension((editorWidth - panelInsets.left - panelInsets.right).coerceAtLeast(1), paneHeight)
        val height = (chromeHeight + paneHeight).coerceAtMost(maxHeight)
        val size = Dimension(editorWidth, height)
        preferredSize = size
        return size
    }

    private fun previewHeightFor(width: Int, budget: Int, gap: Int): Int {
        val panel = preview ?: return 0
        val height = panel.heightFor(width, (budget - gap).coerceAtLeast(0))
        panel.isVisible = height > 0
        return if (height > 0) height + gap else 0
    }

    internal fun requestSave() {
        if (!editable || disposed || saveQueued || saving) return
        saveQueued = true
        input.inputContext?.endComposition()
        SwingUtilities.invokeLater {
            saveQueued = false
            if (disposed || !editable) return@invokeLater
            if (composition.active) {
                showErrorMessage("请先确认输入法候选文字")
                return@invokeLater
            }
            draft.text = input.text
            val dispatch = saveDispatch
            if (dispatch != null) {
                val value = draft.text.trim()
                if (value.isEmpty()) {
                    showErrorMessage("内容不能为空")
                    return@invokeLater
                }
                saving = true
                saveButton.isEnabled = false
                deleteButton.isEnabled = false
                input.isEditable = false
                saveTask = dispatch({ save(value) }) { result ->
                    if (!disposed) {
                        saving = false
                        saveButton.isEnabled = editable
                        deleteButton.isEnabled = editable
                        input.isEditable = editable
                        result.onSuccess { draft.retain = false; saved() }
                            .onFailure { draft.error = it.message ?: "保存失败，请刷新后重试"; showErrorMessage(draft.error.orEmpty()) }
                    }
                }
            } else if (draft.submit(save)) {
                saved()
            } else {
                showErrorMessage(draft.error.orEmpty())
            }
        }
    }

    /** 删除与取消编辑分开；失败保留原生文档，异步完成不得重新开启已失效的编辑。 */
    internal fun requestDelete() {
        val action = delete ?: return
        if (!editable || disposed || saving || composition.active) return
        draft.text = input.text
        fun complete(result: Result<Unit>) {
            if (disposed) return
            saving = false
            input.isEditable = editable
            saveButton.isEnabled = editable
            deleteButton.isEnabled = editable
            result.onSuccess { draft.retain = false; deleted() }
                .onFailure { showErrorMessage(it.message ?: "删除失败，内容已保留") }
        }
        saving = true
        input.isEditable = false
        saveButton.isEnabled = false
        deleteButton.isEnabled = false
        val dispatch = saveDispatch
        if (dispatch != null) saveTask = dispatch(action, ::complete)
        else complete(runCatching(action))
    }

    /** 外部回答、删除或编辑后保留当前文字，撤销尚未派发的保存和删除入口。 */
    fun lockEditing() {
        if (disposed || !editable) return
        editable = false
        draft.text = input.text
        input.isEditable = false
        saveButton.isVisible = false
        deleteButton.isVisible = false
        cancelButton.text = "关闭"
        showErrorMessage("问题已变化，当前文字已保留")
    }

    fun dismissSafely() {
        input.inputContext?.endComposition()
        draft.text = input.text
        dismiss()
    }

    override fun close() {
        if (disposed) return
        input.inputContext?.endComposition()
        draft.text = input.text
        disposed = true
        saveTask?.close()
        fonts.close()
        preview?.close()
        input.document.removeDocumentListener(documentListener)
        input.removeInputMethodListener(inputListener)
        actionMap.clear()
    }

    private fun updateDraftAndMetrics() {
        draft.text = input.text
        if (error.text.isNotEmpty()) error.text = ""
        scheduleMetricsChanged()
    }

    internal fun showErrorMessage(message: String) {
        error.text = message
        error.foreground = UnderstandingUiStyle.failure
        scheduleMetricsChanged()
    }

    private fun scheduleMetricsChanged() {
        if (disposed || metricsQueued) return
        metricsQueued = true
        SwingUtilities.invokeLater {
            metricsQueued = false
            if (disposed) return@invokeLater
            error.foreground = UnderstandingUiStyle.failure
            revalidate()
            onMetricsChanged()
        }
    }

    private fun bind(key: String, name: String, action: () -> Unit) {
        val stroke = KeyStroke.getKeyStroke(key)
        input.inputMap.put(stroke, name)
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, name)
        actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    private fun updateErrorHeight(width: Int, panelInsets: java.awt.Insets) {
        if (error.text.isEmpty()) {
            error.isVisible = false
            error.preferredSize = Dimension((width - panelInsets.left - panelInsets.right).coerceAtLeast(1), 0)
            return
        }
        error.isVisible = true
        val errorInsets = error.insets
        val errorWidth = (width - panelInsets.left - panelInsets.right - errorInsets.left - errorInsets.right).coerceAtLeast(1)
        val rows = wrappedRows(error, errorWidth)
        val lineHeight = error.getFontMetrics(error.font).height.coerceAtLeast(1)
        error.preferredSize = Dimension(
            (width - panelInsets.left - panelInsets.right).coerceAtLeast(1),
            rows * lineHeight + errorInsets.top + errorInsets.bottom,
        )
    }

    private fun wrappedRows(area: JTextArea, width: Int): Int {
        val view = area.ui.getRootView(area)
        view.setSize(width.coerceAtLeast(1).toFloat(), MAX_MEASURE_HEIGHT)
        val lineHeight = area.getFontMetrics(area.font).height.coerceAtLeast(1)
        return ceil(view.getPreferredSpan(View.Y_AXIS) / lineHeight).toInt().coerceAtLeast(1)
    }

    private companion object {
        const val MAX_VISIBLE_ROWS = 8
        const val MAX_MEASURE_HEIGHT = 32768f
    }
}
