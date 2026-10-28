package com.lessplain.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.util.ui.JBUI
import com.lessplain.core.BackgroundReadDispatch
import com.lessplain.core.LatestBackgroundRead
import com.lessplain.ide.QuestionOriginTarget
import com.lessplain.ide.QuestionOrigin
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.readQuestionOrigin
import java.awt.Component
import javax.swing.SwingUtilities

/** 每个画布宿主一个按需回看窗；已打开才读取，切换目标、关闭后的旧结果不会回写。 */
internal class QuestionOriginWindow(
    private val service: UnderstandingService,
    private val project: Project,
    private val dispatch: BackgroundReadDispatch<QuestionOrigin> = service::readBackground,
    private val present: (JBPopup, Component) -> Unit = { popup, owner -> popup.showInCenterOf(owner) },
) : AutoCloseable {
    private var target: QuestionOriginTarget? = null
    private var popup: JBPopup? = null
    internal var panel: QuestionOriginPanel? = null
        private set
    private var loader: LatestBackgroundRead<QuestionOrigin>? = null
    private var closed = false
    private val subscription = service.onChanged { refresh() }

    fun show(owner: Component, requested: QuestionOriginTarget) = onEdt {
        if (project.isDisposed || !owner.isShowing) return@onEdt
        target = requested
        if (popup == null) {
            val content = QuestionOriginPanel(project)
            val created = try { nativePopupBuilder(content, content.snapshot.text, "原始提问", cancelOnOutside = false)
                .setResizable(true).setMinSize(JBUI.size(360, 260))
                .setBelongsToGlobalPopupStack(false)
                .setDimensionServiceKey(project, "Lessplain.QuestionOrigin", false).createPopup()
            } catch (failure: Throwable) { content.close(); throw failure }
            panel = content; popup = created
            created.addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (popup === created) {
                        loader?.close(); loader = null; popup = null; panel = null
                    }
                    content.close()
                }
            })
            loader = LatestBackgroundRead(dispatch) { result ->
                result.onSuccess { panel?.refresh(it) }
                    .onFailure { panel?.failed(it.message ?: "来源记录不存在") }
            }
            try { present(created, owner) } catch (failure: Throwable) { dismiss(); throw failure }
        } else {
            SwingUtilities.getWindowAncestor(popup?.content)?.toFront()
            panel?.snapshot?.text?.requestFocusInWindow()
        }
        refresh()
    }

    private fun refresh() {
        if (closed || popup == null || project.isDisposed) return
        val requested = target ?: return
        panel?.loading()
        loader?.request { readQuestionOrigin(service.repository, requested) }
    }

    fun removeDeleted(ids: Set<String>) = onEdt { if (target?.recordId in ids) { dismiss(); target = null } }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) { if (!closed) action() }
        else SwingUtilities.invokeLater { if (!closed) action() }
    }

    private fun dismiss() {
        loader?.close(); loader = null
        val current = popup; val content = panel
        popup = null; panel = null
        current?.cancel()
        if (current != null && !current.isDisposed) current.dispose()
        content?.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        subscription.close(); dismiss()
    }
}
