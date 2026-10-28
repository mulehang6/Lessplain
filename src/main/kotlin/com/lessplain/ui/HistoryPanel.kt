package com.lessplain.ui

import com.intellij.ui.SearchTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lessplain.ide.UnderstandingService
import java.awt.BorderLayout
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** 搜索组件在异步读取期间始终存活，输入法提交后才搜索；更新只修改列表和分页。 */
internal class HistoryPanel(private val service: UnderstandingService, initialQuery: String = "", private val queryChanged: (String) -> Unit) : JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))), AutoCloseable {
    val search = SearchTextField()
    val records = RecordListPanel(service) { item ->
        if (item.pending != null) service.showResult(item.pending)
        else item.recordId?.let(service.storage::record)?.let(service::showRoot)
    }
    val paging = HistoryPagingPanel()
    private val heading = JLabel("历史记录")
    private val readOnly = JLabel("此项目已在另一 IDE 实例中打开，当前只读")
    private var composing = false
    private var closed = false
    private var query = initialQuery
    private val debounce = Timer(180) { submitQuery() }.apply { isRepeats = false }
    private val fonts = CanvasUiFonts(listOf(heading, readOnly)) { heading.font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD) }
    init {
        border = JBUI.Borders.empty(UnderstandingUiMetrics.PAGE_PADDING)
        search.text = initialQuery
        search.textEditor.emptyText.text = "搜索标题或问题"
        heading.font = UIUtil.getLabelFont().deriveFont(java.awt.Font.BOLD)
        readOnly.isVisible = service.readOnly
        add(JPanel(BorderLayout(0, JBUI.scale(PopupUi.GAP))).apply {
            add(heading, BorderLayout.NORTH); add(search, BorderLayout.CENTER); add(readOnly, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(records, BorderLayout.CENTER); add(paging, BorderLayout.SOUTH)
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) { debounce.restart() }
            override fun removeUpdate(event: DocumentEvent) { debounce.restart() }
            override fun changedUpdate(event: DocumentEvent) { debounce.restart() }
        })
        search.textEditor.addInputMethodListener(object : InputMethodListener {
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                val text = event.text
                composing = text != null && text.endIndex - text.beginIndex > event.committedCharacterCount
                if (composing) debounce.stop() else debounce.restart()
            }
            override fun caretPositionChanged(event: InputMethodEvent) = Unit
        })
        search.textEditor.addActionListener { submitQuery() }
    }
    internal fun submitQuery() {
        debounce.stop()
        if (closed || composing || query == search.text) return
        query = search.text
        queryChanged(query)
    }
    fun update(result: Result<UnderstandingPanelData>?, page: Int, back: () -> Unit, forward: () -> Unit) {
        readOnly.isVisible = service.readOnly
        val data = result?.getOrNull()
        records.records(data?.history.orEmpty(), data?.historyUnread.orEmpty())
        records.list.emptyText.text = when {
            result == null -> "正在搜索…"
            result.isFailure -> "历史无法加载：${result.exceptionOrNull()?.message}"
            else -> "暂无记录"
        }
        paging.update(page, data?.historyHasNext == true, back, forward)
        paging.previousButton.isEnabled = data != null && page > 0
    }
    override fun close() { closed = true; debounce.stop(); records.close(); fonts.close() }
}
