package com.lessplain.ui

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
import com.lessplain.core.BackgroundReadDispatch
import java.util.concurrent.CancellationException

/** 草稿不依赖界面工具包；关闭保留，明确取消才丢弃。 */
internal class CanvasTextDraft(initial: String) {
    var text = initial
    var error: String? = null
    var retain = true
    var saving = false
        private set
    var committed = false
        private set
    private val listeners = linkedSetOf<() -> Unit>()

    fun listen(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun beginSave(): Boolean {
        if (saving || committed) return false
        saving = true; error = null
        listeners.toList().forEach { it() }
        return true
    }

    fun complete(result: Result<Unit>) {
        saving = false
        result.onSuccess { retain = false; committed = true }
            .onFailure { error = it.message ?: "保存失败，请刷新后重试" }
        listeners.toList().forEach { it() }
    }

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
    private val saveDispatch: BackgroundReadDispatch<Unit>? = null,
) : JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))), AutoCloseable {
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
    internal val saveButton = JButton("保存").apply { addActionListener { requestSave() } }
    internal val cancelButton = JButton("取消").apply { addActionListener { cancel() } }

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
    private val preview = selectionPreview?.let { SelectedCodePreviewPanel(it, changed = ::scheduleMetricsChanged) }
    private val bottom = JPanel(BorderLayout()).apply {
        add(error, BorderLayout.NORTH)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(saveButton)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(PopupUi.GAP)))
            add(cancelButton)
        }, BorderLayout.SOUTH)
    }
    private val fonts = CanvasUiFonts(
        listOf(input, error, saveButton, cancelButton),
        onRefreshed = ::scheduleMetricsChanged,
    )
    private var saveQueued = false
    private var metricsQueued = false
    private var disposed = false
    private val draftState = draft.listen(::refreshDraftState)
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
        border = JBUI.Borders.empty(PopupUi.PADDING)
        input.enableInputMethods(true)
        input.accessibleContext.accessibleName = title
        input.document.addDocumentListener(documentListener)
        input.addInputMethodListener(inputListener)
        add(scrollPane ?: input, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        preview?.let { add(it, BorderLayout.NORTH) }
        bind("control ENTER", "save") { if (!composition.active) requestSave() }
        bind("ESCAPE", "dismiss") { if (!composition.active) dismissSafely() }
        if (!multiline) (input as JBTextField).addActionListener { if (!composition.active) requestSave() }
        refreshDraftState()
    }

    override fun addNotify() {
        super.addNotify()
        rootPane?.defaultButton = saveButton
    }

    /** 测量 Swing 折行行数，使输入区显示一至八行。 */
    internal fun preferredSizeFor(width: Int, availableHeight: Int): Dimension {
        val editorWidth = width.coerceAtLeast(1)
        val maxHeight = availableHeight.coerceAtLeast(1)
        val panelInsets = insets
        updateErrorHeight(editorWidth, panelInsets)
        val contentWidth = (editorWidth - panelInsets.left - panelInsets.right).coerceAtLeast(1)
        val gap = JBUI.scale(PopupUi.GAP)
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
        if (disposed || saveQueued || draft.saving || draft.committed) return
        saveQueued = true
        input.inputContext?.endComposition()
        SwingUtilities.invokeLater {
            saveQueued = false
            if (disposed) return@invokeLater
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
                if (!draft.beginSave()) return@invokeLater
                // 保存由项目任务拥有；关窗只释放界面，不能丢掉已提交事务的成功通知。
                dispatch({ save(value) }) { result ->
                    draft.complete(result)
                    result.onSuccess { saved() }
                }
            } else if (draft.submit(save)) {
                saved()
            } else {
                showErrorMessage(draft.error.orEmpty())
            }
        }
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
        draftState.close()
        fonts.close()
        preview?.close()
        input.document.removeDocumentListener(documentListener)
        input.removeInputMethodListener(inputListener)
        actionMap.clear()
    }

    private fun refreshDraftState() {
        if (disposed) return
        saveButton.isEnabled = !draft.saving && !draft.committed
        cancelButton.isEnabled = !draft.saving
        input.isEditable = !draft.saving && !draft.committed
        if (draft.committed) dismiss()
        else draft.error?.let(::showErrorMessage)
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
