package com.lessplain.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.uiRead
import com.lessplain.model.UnderstandingRecord
import com.lessplain.tutorial.TutorialEditorView
import com.lessplain.tutorial.TutorialService
import java.awt.BorderLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel

/** 整个工具窗口中的细讲宿主；切换同层节点不受执行边和正文阅读进度约束。 */
internal class NodeExplanationPanel(
    private val service: UnderstandingService,
    val record: UnderstandingRecord,
    initialNodeId: String,
    back: () -> Unit,
    private val selected: (String) -> Unit,
) : JPanel(BorderLayout()), AutoCloseable {
    private val tutorials = service.project.getService(TutorialService::class.java)
    internal val nodes = ComboBox(record.graph.nodes.map { it.id }.toTypedArray())
    private val body = JPanel(BorderLayout())
    private val openEditor = JButton("在编辑器中打开")
    internal var view: TutorialEditorView? = null
        private set
    var nodeId: String = initialNodeId
        private set
    private var closed = false
    private val subscription = tutorials.onChanged { id ->
        if (!closed && record.graph.nodes.find { it.id == nodeId }?.tutorialId == id && tutorials.store.isUnavailable(id)) showNode(nodeId)
    }

    init {
        nodes.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                val node = record.graph.nodes.find { it.id == value }
                text = node?.label.orEmpty() + if (node?.tutorialId == null) " · 暂无细讲" else ""
                return this
            }
        }
        nodes.selectedItem = initialNodeId
        nodes.accessibleContext.accessibleName = "切换主节点"
        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(8)
            add(JButton("返回主图").apply { addActionListener { back() } }, BorderLayout.WEST)
            add(nodes, BorderLayout.CENTER)
            add(openEditor, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        addHierarchyListener { event ->
            if (!closed && event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) view?.active(isShowing)
        }
        nodes.addActionListener {
            (nodes.selectedItem as? String)?.takeIf { it != nodeId }?.let { id ->
                showNode(id)
                selected(id)
            }
        }
        openEditor.addActionListener {
            record.graph.nodes.find { it.id == nodeId }?.tutorialId?.let { id -> tutorials.attempt { tutorials.open(id) } }
        }
        showNode(initialNodeId)
    }

    private fun showNode(id: String) {
        view?.let { uiRead { it.close() } }
        view = null
        nodeId = id
        body.removeAll()
        val tutorialId = record.graph.nodes.find { it.id == id }?.tutorialId
        openEditor.isEnabled = tutorialId != null && !tutorials.store.isUnavailable(tutorialId)
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
            openEditor.isEnabled = false
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
