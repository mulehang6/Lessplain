package com.lessplain.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import java.awt.Component
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.AbstractButton

internal data class NativeMenuItem(val label: String, val enabled: Boolean = true, val icon: javax.swing.Icon? = null, val perform: () -> Unit)

/** 统一走 IDE action popup 的行距、圆角、主题选中色及键盘行为。 */
internal fun showNativeMenu(
    component: Component?, items: List<NativeMenuItem>, point: Point? = null, title: String? = null, project: Project? = null,
) {
    if (component != null && !component.isShowing) return
    val context = if (component != null) DataManager.getInstance().getDataContext(component)
        else SimpleDataContext.getProjectContext(project ?: return)
    val group = DefaultActionGroup().apply {
        items.forEach { item -> add(object : DumbAwareAction(item.label) {
            init { templatePresentation.isEnabled = item.enabled; templatePresentation.icon = item.icon }
            override fun actionPerformed(event: AnActionEvent) { item.perform() }
        }) }
    }
    val popup = JBPopupFactory.getInstance().createActionGroupPopup(title, group, context,
        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
    when {
        component == null -> popup.showCenteredInCurrentWindow(project!!)
        point != null -> popup.show(RelativePoint(component, point))
        component is AbstractButton -> popup.showUnderneathOf(component)
        else -> popup.showInBestPositionFor(context)
    }
}

/** 兼容 Swing 的鼠标/键盘 popup 触发，实际显示仍由 IDE 标准菜单负责。 */
internal fun installNativeMenu(component: JComponent, items: () -> List<NativeMenuItem>) {
    component.componentPopupMenu = object : JPopupMenu() {
        override fun show(invoker: Component, x: Int, y: Int) = showNativeMenu(invoker, items(), Point(x, y))
    }
}
