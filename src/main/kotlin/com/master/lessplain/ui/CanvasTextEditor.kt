package com.master.lessplain.ui

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

/** Drafts are independent of the UI toolkit; dismiss retains, explicit cancel discards. */
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
        } catch (failure: Exception) {
            error = failure.message ?: "保存失败，请刷新后重试"
            false
        }
    }
}

/** Tracks preedit separately from committed text; it never consumes input-method events. */
internal class EditorComposition {
    var active = false
        private set

    fun changed(total: Int, committed: Int) {
        active = total > committed
    }
}

/** Native text components keep the IDE's input-method chain, outside Compose's event bridge. */
internal class CanvasTextEditor(
    title: String,
    private val draft: CanvasTextDraft,
    private val multiline: Boolean,
    private val save: (String) -> Unit,
    private val saved: () -> Unit,
    private val dismiss: () -> Unit,
    private val cancel: () -> Unit,
    private val onMetricsChanged: () -> Unit = {},
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
    private val bottom = JPanel(BorderLayout()).apply {
        add(error, BorderLayout.NORTH)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(UnderstandingUiMetrics.GAP), 0)).apply {
            add(saveButton)
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
        input.accessibleContext.accessibleName = title
        input.document.addDocumentListener(documentListener)
        input.addInputMethodListener(inputListener)
        add(scrollPane ?: input, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        bind("control ENTER", "save") { if (!composition.active) requestSave() }
        bind("ESCAPE", "dismiss") { if (!composition.active) dismissSafely() }
        if (!multiline) (input as JBTextField).addActionListener { if (!composition.active) requestSave() }
    }

    override fun uiSettingsChanged(uiSettings: UISettings) {
        fonts.refresh()
    }

    /** Measures wrapped Swing rows and keeps the input area between one and eight visible rows. */
    internal fun preferredSizeFor(width: Int, availableHeight: Int): Dimension {
        val editorWidth = width.coerceAtLeast(1)
        val maxHeight = availableHeight.coerceAtLeast(1)
        val panelInsets = insets
        updateErrorHeight(editorWidth, panelInsets)
        if (!multiline) {
            visibleRowCount = 1
            scrollRequired = false
            val height = panelInsets.top + panelInsets.bottom + input.preferredSize.height +
                bottom.preferredSize.height + JBUI.scale(UnderstandingUiMetrics.SECTION_GAP)
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
        val chromeHeight = panelInsets.top + panelInsets.bottom + bottom.preferredSize.height +
            JBUI.scale(UnderstandingUiMetrics.SECTION_GAP)
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

    internal fun requestSave() {
        if (disposed || saveQueued) return
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
            if (draft.submit(save)) {
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
        fonts.close()
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
