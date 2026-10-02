package com.master.understanding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.master.understanding.ide.UnderstandingService
import java.awt.Component
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseEvent
import java.util.WeakHashMap
import javax.swing.SwingUtilities
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

private val recentPopups = WeakHashMap<Component, ThemedCanvasPopup>()

/** Shares Jewel record rows with history; one trigger click always closes an already-open popup. */
internal fun showRecentRecordPopup(service: UnderstandingService, anchor: Component) {
    recentPopups.remove(anchor)?.let { it.close(); return }
    val records = service.repository.recentRootRecords("", 6)
    var popup: ThemedCanvasPopup? = null
    val visibility = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !anchor.isShowing) popup?.close()
    }
    anchor.addHierarchyListener(visibility)
    popup = ThemedCanvasPopup(anchor, Point(0, anchor.height), Dimension(360, maxOf(1, records.size) * 40 + 16),
        onClosed = { recentPopups.remove(anchor); anchor.removeHierarchyListener(visibility) },
        mayCancel = {
            val source = (EventQueue.getCurrentEvent() as? MouseEvent)?.source as? Component
            source == null || (source !== anchor && !SwingUtilities.isDescendingFrom(source, anchor))
        }) {
        var selected by remember { mutableIntStateOf(records.indexOfFirst { it.id == service.activeRecordId }.coerceAtLeast(0)) }
        val focus = remember { FocusRequester() }
        fun choose(index: Int) {
            records.getOrNull(index)?.let(service::showRoot)
            popup?.close()
        }
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(8.dp)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown || records.isEmpty()) false else when (it.key) {
                    Key.DirectionDown -> { selected = (selected + 1) % records.size; true }
                    Key.DirectionUp -> { selected = (selected - 1 + records.size) % records.size; true }
                    Key.Enter -> { choose(selected); true }
                    else -> false
                }
            }.focusRequester(focus).focusable(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (records.isEmpty()) Text("暂无记录")
            records.forEachIndexed { index, record ->
                RecordCandidateRow(record, index == selected, onClick = { choose(index) })
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
    recentPopups[anchor] = popup
}
