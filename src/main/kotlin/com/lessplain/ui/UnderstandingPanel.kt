package com.lessplain.ui

import com.intellij.openapi.Disposable
import com.intellij.util.ui.JBUI
import com.lessplain.core.LatestBackgroundRead
import com.lessplain.ide.GraphLayerKey
import com.lessplain.ide.PanelCanvasRoute
import com.lessplain.ide.PanelPage
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.UnderstandingRecord
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JLabel
import javax.swing.JPanel

private data class PreparedPanel(
    val request: UnderstandingPanelRequest,
    val data: UnderstandingPanelData,
    val canvas: QuestionCanvas?,
)

/** 原生组件持续存活；读取只更新列表或实际发生修订的画布，不卸载搜索输入框。 */
class UnderstandingPanel(private val service: UnderstandingService) : JPanel(BorderLayout()), Disposable {
    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val message = JLabel("正在加载…")
    internal val history = HistoryPanel(service) { query ->
        historySearch = query
        historyPage = 0
        refresh()
    }
    private var historySearch = ""
    private var historyPage = 0
    private var disposed = false
    private var request: UnderstandingPanelRequest? = null
    private var loaded: PreparedPanel? = null
    private var canvasKey: List<Any?>? = null
    internal var canvas: PanelCanvasView? = null
        private set
    private val loader = LatestBackgroundRead<PreparedPanel>(service::readBackground, ::publish)
    private val subscription = service.onChanged(::refresh)

    init {
        message.border = JBUI.Borders.empty(UnderstandingUiMetrics.PAGE_PADDING)
        content.add(history, "history")
        content.add(message, "message")
        add(content, BorderLayout.CENTER)
        refresh()
    }

    private fun refresh() {
        if (disposed) return
        val historyVisible = service.panelPage == PanelPage.HISTORY || service.panelRecordId == null
        val next = UnderstandingPanelRequest(
            if (historyVisible) PanelPage.HISTORY else PanelPage.CANVAS,
            service.panelRecordId, service.panelRootRecordId, service.panelLayers.toList(),
            if (historyVisible) historySearch else "", historyPage,
        )
        if (request != next) {
            request = next
            loaded = null
            if (historyVisible) {
                closeCanvas()
                updateHistory(null)
            } else if (canvasKey?.get(0) != (next.layers.lastOrNull()?.recordId ?: next.activeRecordId) ||
                canvasKey?.get(2) != next.layers.lastOrNull()?.nodeId) showMessage("正在加载…")
        }
        val pending = service.resultInbox.pending()
        loader.request {
            val data = readUnderstandingPanelData(service.repository, next, pending)
            (data.history + listOfNotNull(data.selected, data.displayed)).forEach(service.storage::remember)
            data.hierarchy.flatMap { it.layers }.distinctBy { it.recordId }.forEach { service.storage.loadRecord(it.recordId) }
            data.hierarchyRootId?.let { service.storage.loadRecord(it) }
            data.displayed?.let { service.storage.rememberBrowse(it.id, data.initialBrowse ?: service.storage.loadBrowse(it.id)) }
            val questionCanvas = next.layers.lastOrNull()?.let { layer ->
                data.displayed?.let { record ->
                    buildQuestionCanvas(service, record, layer.nodeId,
                        questionsForNode(service, record, layer.nodeId), record.revision).also { canvas ->
                        canvas.sources.values.forEach { service.storage.remember(it.record) }
                    }
                }
            }
            PreparedPanel(next, data, questionCanvas)
        }
    }

    private fun publish(result: Result<PreparedPanel>) {
        if (disposed) return
        result.onFailure { failure ->
            if (request?.page == PanelPage.HISTORY) updateHistory(Result.failure(failure))
            else if (loaded == null || canvas == null) showMessage("图无法加载：${failure.message}")
            else service.storageFailed(failure)
        }.onSuccess { prepared ->
            if (prepared.request != request) return@onSuccess
            loaded = prepared
            val data = prepared.data
            if (prepared.request.page == PanelPage.HISTORY) {
                if (data.history.isEmpty() && historyPage > 0) {
                    historyPage--
                    refresh()
                } else updateHistory(Result.success(data))
                return@onSuccess
            }
            val record = data.displayed
            if (record == null) {
                service.panelRecordId = null
                service.showHistory()
                return@onSuccess
            }
            val key = listOf(record.id, record.revision, prepared.request.layers.lastOrNull()?.nodeId, prepared.canvas)
            if (canvasKey != key) {
                closeCanvas()
                com.lessplain.core.cancellationAwareResult {
                    PanelCanvasView(service, record, prepared.request.layers.lastOrNull()?.nodeId,
                        data.initialBrowse, prepared.canvas, ::openQuestions)
                }.onSuccess {
                    canvas = it
                    canvasKey = key
                    content.add(it, "canvas")
                }.onFailure {
                    showMessage("图无法加载：${it.message}")
                    return@onSuccess
                }
            }
            canvas?.update(data.hierarchy, prepared.request.layers, service.panelFocusQuestionId,
                service.canReturnToPrevious, service.returnLabel, ::navigateTo, service::returnToPrevious)
            cards.show(content, "canvas")
            service.panelSession.remember(PanelCanvasRoute(data.hierarchyRootId ?: record.id, prepared.request.layers))
            revalidate()
            repaint()
        }
    }

    private fun updateHistory(result: Result<UnderstandingPanelData>?) {
        history.update(result, historyPage, {
            if (historyPage > 0) { historyPage--; refresh() }
        }, { historyPage++; refresh() })
        cards.show(content, "history")
    }

    private fun navigateTo(index: Int) {
        val prepared = loaded ?: return
        val destination = prepared.data.hierarchy.getOrNull(index) ?: return
        val layer = destination.layers.lastOrNull()
        val target = (layer?.recordId ?: prepared.data.hierarchyRootId)?.let(service.storage::record) ?: return
        service.panelLayers = destination.layers
        service.panelRootRecordId = prepared.data.hierarchyRootId.takeIf { destination.layers.isNotEmpty() }
        service.panelFocusQuestionId = null
        selectTarget(target, layer?.nodeId)
    }

    private fun openQuestions(record: UnderstandingRecord, nodeId: String) {
        if (service.panelLayers.isEmpty()) service.panelRootRecordId = record.id
        service.panelLayers = service.panelLayers + FollowUpLayer(record.id, nodeId)
        service.panelFocusQuestionId = null
        selectTarget(record, nodeId)
    }

    private fun selectTarget(record: UnderstandingRecord, nodeId: String?) {
        service.panelRecordId = record.id
        service.select(record, nodeId,
            navigateSource = !service.graphStates.get(GraphLayerKey(record.id, nodeId)).navigationPinned)
        service.notifyChanged()
    }

    private fun showMessage(text: String) {
        message.text = text
        cards.show(content, "message")
    }

    private fun closeCanvas() {
        canvas?.let { content.remove(it); it.close() }
        canvas = null
        canvasKey = null
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        subscription.close()
        loader.close()
        history.close()
        closeCanvas()
        removeAll()
    }
}
