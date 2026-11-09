package com.lessplain.ui

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.ui.JBUI
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.KeyStroke

/** 24 UI 像素的原生工具栏动作，悬停、按下和鼠标释放均交给平台处理。 */
internal fun compactIconButton(icon: Icon, label: String, clicked: () -> Unit): ActionButton {
    val action = object : DumbAwareAction(label, null, icon) {
        override fun actionPerformed(event: AnActionEvent) = clicked()
    }
    return ActionButton(action, null, "Lessplain.CompactActions", JBUI.size(24)).apply {
        maximumSize = preferredSize
        accessibleContext.accessibleName = label
        isFocusable = true
        // 平台已在 Space 松开时激活，避免另绑 Space 导致一次按键执行两次。
        inputMap.put(KeyStroke.getKeyStroke("released ENTER"), "activate")
        actionMap.put("activate", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) { click() }
        })
    }
}
