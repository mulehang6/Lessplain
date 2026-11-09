package com.lessplain.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.uiRead
import com.lessplain.model.UnderstandingRecord
import com.lessplain.tutorial.TutorialEditorView
import com.lessplain.tutorial.TutorialService
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

/** 当前节点的正文独占工具窗口；切换节点先返回保留视角的主图。 */
internal class NodeExplanationPanel(
    private val service: UnderstandingService,
    val record: UnderstandingRecord,
    initialNodeId: String,
    back: () -> Unit,
) : JPanel(BorderLayout()), AutoCloseable {
    private val tutorials = service.project.getService(TutorialService::class.java)
    private val body = JPanel(BorderLayout())
    internal var view: TutorialEditorView? = null
        private set
    var nodeId: String = initialNodeId
        private set
    private var closed = false
    private val subscription = tutorials.onChanged { id ->
        if (!closed && record.graph.nodes.find { it.id == nodeId }?.tutorialId == id && tutorials.store.isUnavailable(id)) showNode(nodeId)
    }

    init {
        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(compactIconButton(AllIcons.Actions.Back, "返回主图", back), BorderLayout.WEST)
            add(JLabel(record.graph.nodes.find { it.id == nodeId }?.label.orEmpty()), BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        addHierarchyListener { event ->
            if (!closed && event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) view?.active(isShowing)
        }
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "backToGraph")
        actionMap.put("backToGraph", object : javax.swing.AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) = back()
        })
        showNode(initialNodeId)
    }

    private fun showNode(id: String) {
        view?.let { uiRead { it.close() } }
        view = null
        nodeId = id
        body.removeAll()
        val tutorialId = record.graph.nodes.find { it.id == id }?.tutorialId
        if (tutorialId == null) body.add(JBLabel("此节点尚未准备细讲，可以返回主图继续阅读。").apply { border = JBUI.Borders.empty(16) })
        else try {
            val next = uiRead { TutorialEditorView(service.project, tutorialId, panel = true) }
            view = next
            next.active(isShowing)
            val toolbar = ActionManager.getInstance().createActionToolbar("Lessplain.NodeExplanation", next.tabActions, true)
            toolbar.targetComponent = next
            body.add(toolbar.component, BorderLayout.NORTH)
            body.add(next, BorderLayout.CENTER)
        } catch (failure: IllegalArgumentException) {
            body.add(JBLabel("细讲不可用：${failure.message}").apply { border = JBUI.Borders.empty(16) })
        }
        body.revalidate()
        body.repaint()
    }

    override fun close() {
        if (closed) return
        closed = true
        subscription.close()
        view?.let { uiRead { it.close() } }
        view = null
        removeAll()
    }
}
