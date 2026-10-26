package com.lessplain.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.ComponentPopupBuilder
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.MaskProvider
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent

/** 共享平台标题栏与外轮廓，内容状态和关闭后的保存规则仍由各窗口负责。 */
internal fun nativePopupBuilder(
    content: JComponent,
    focus: JComponent?,
    title: String,
    cancelOnOutside: Boolean,
    canClose: () -> Boolean = { true },
): ComponentPopupBuilder = JBPopupFactory.getInstance().createComponentPopupBuilder(content, focus)
    .setTitle(title)
    .setCancelButton(popupCloseIcon)
    .setMaskProvider(popupMask)
    .setShowBorder(true).setShowShadow(true)
    .setMovable(true).setFocusable(true).setRequestFocus(true)
    .setCancelKeyEnabled(true)
    .setCancelOnClickOutside(cancelOnOutside)
    .setCancelOnWindowDeactivation(cancelOnOutside)
    .setCancelOnOtherWindowOpen(false)
    .setCancelCallback { canClose() }

/** 同一关闭入口必须提供不同的常态和悬停资源，由平台处理命中与状态切换。 */
internal val popupCloseIcon = IconButton("关闭", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)

/** 平台在每次调整窗口大小时重新请求轮廓，标题和正文共用同一圆角。 */
internal val popupMask = MaskProvider { size ->
    val arc = JBUI.scale(12).toDouble()
    RoundRectangle2D.Double(0.0, 0.0, size.width.toDouble(), size.height.toDouble(), arc, arc)
}

internal object PopupUi {
    const val PADDING = 12
    const val GAP = 8
    const val ICON_GAP = 4
    const val ICON_SIZE = 28
}

/** 保留主题提供的悬停、按下和禁用反馈，图标的命中区不少于 28 UI 像素。 */
internal fun popupIconButton(icon: Icon, description: String, count: Int = 0, action: () -> Unit): JButton =
    JButton(if (count > 0) count.toString() else null, icon).apply {
        toolTipText = description
        accessibleContext.accessibleName = description
        margin = JBUI.insets(0, PopupUi.ICON_GAP)
        // 普通 JButton 的主题最小宽度适合文字表单，图标入口必须按内容独立测量。
        val countWidth = if (count > 0) getFontMetrics(font).stringWidth(count.toString()) + JBUI.scale(PopupUi.ICON_GAP) else 0
        preferredSize = Dimension(JBUI.scale(PopupUi.ICON_SIZE) + countWidth, JBUI.scale(PopupUi.ICON_SIZE))
        minimumSize = preferredSize
        maximumSize = preferredSize
        addActionListener { action() }
    }
