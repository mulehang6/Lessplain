package com.lessplain.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.util.ui.JBUI
import com.lessplain.core.LatestBackgroundRead
import com.lessplain.ide.UnderstandingService
import java.awt.Component
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.WeakHashMap

private val recordPopups = WeakHashMap<Component, JBPopup>()

/** 最近列表的读取与订阅随原生浮窗关闭释放。 */
internal fun showRecentRecordPopup(service: UnderstandingService, anchor: Component) {
    recordPopups.remove(anchor)?.let { it.cancel(); return }
    if (!anchor.isShowing) return
    var popup: JBPopup? = null
    val content = RecordListPanel(service) { item ->
        item.recordId?.let(service.storage::record)?.let(service::showRoot)
        popup?.cancel()
    }.apply { preferredSize = JBUI.size(420, 300) }
    val reader = LatestBackgroundRead<List<RecordListItem>>(service::readBackground) { result ->
        result.onSuccess(content::items).onFailure { service.contentActions.notifyQuestionCopy(it.message ?: "无法读取记录", true) }
    }
    fun refresh() = reader.request {
        service.repository.recentRootRecords("", 6).map {
            service.storage.remember(it); RecordListItem(it.id, it.displayTitle ?: it.question, it.id)
        }
    }
    val subscription = service.onChanged(::refresh)
    val visibility = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !anchor.isShowing && popup != null) popup?.cancel()
    }
    try {
        popup = nativePopupBuilder(content, content.list, "最近", true) { !service.deleteConfirming }
            .setResizable(true).createPopup()
    } catch (failure: Throwable) { subscription.close(); reader.close(); content.close(); throw failure }
    val created = requireNotNull(popup)
    created.addListener(object : JBPopupListener {
        override fun onClosed(event: LightweightWindowEvent) {
            if (recordPopups[anchor] === popup) recordPopups.remove(anchor)
            subscription.close(); reader.close(); content.close(); anchor.removeHierarchyListener(visibility)
        }
    })
    anchor.addHierarchyListener(visibility)
    recordPopups[anchor] = created
    refresh()
    try { created.showUnderneathOf(anchor) } catch (failure: Throwable) {
        created.cancel(); subscription.close(); reader.close(); content.close(); anchor.removeHierarchyListener(visibility); recordPopups.remove(anchor)
        throw failure
    }
}
