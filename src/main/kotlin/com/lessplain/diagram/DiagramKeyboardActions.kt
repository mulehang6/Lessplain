package com.lessplain.diagram

import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/** 画布获得键盘焦点时，也能执行悬停动作。 */
internal object DiagramKeyboardActions {
    fun move(diagram: UnderstandingDiagram, delta: Int) {
        if (diagram.routeNavigation?.moveCandidate(delta) == true) return
        val ids = diagram.layout.nodes.keys.toList()
        if (ids.isEmpty()) return
        val index = ids.indexOf(diagram.currentNodeId).coerceAtLeast(0)
        diagram.selectUnderstandingNode(ids[(index + delta + ids.size) % ids.size], notify = true)
    }

    fun install(component: JComponent, onMove: (Int) -> Unit, onCopy: () -> Unit, onQuestions: () -> Unit,
        onCancel: () -> Unit = {}) {
        val inputs = component.getInputMap(JComponent.WHEN_FOCUSED)
        inputs.put(KeyStroke.getKeyStroke("RIGHT"), "next-node")
        inputs.put(KeyStroke.getKeyStroke("DOWN"), "next-node")
        inputs.put(KeyStroke.getKeyStroke("LEFT"), "previous-node")
        inputs.put(KeyStroke.getKeyStroke("UP"), "previous-node")
        inputs.put(KeyStroke.getKeyStroke("control C"), "copy-node")
        inputs.put(KeyStroke.getKeyStroke("ENTER"), "open-questions")
        inputs.put(KeyStroke.getKeyStroke("ESCAPE"), "cancel-route")
        component.actionMap.put("next-node", action { onMove(1) })
        component.actionMap.put("previous-node", action { onMove(-1) })
        component.actionMap.put("copy-node", action(onCopy))
        component.actionMap.put("open-questions", action(onQuestions))
        component.actionMap.put("cancel-route", action(onCancel))
    }

    private fun action(run: () -> Unit): AbstractAction = object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent) = run()
    }
}
