package com.master.lessplain.ui

import com.intellij.openapi.ui.popup.JBPopup
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Point

/** JBPopup 返回内容原点，限制位置时需使用整个原生窗口的原点。 */
internal fun nativePopupLocation(popup: JBPopup): Point {
    val location = popup.locationOnScreen
    val insets = popup.content.insets
    return Point(location.x - insets.left, location.y - insets.top)
}

/** 公开尺寸和位置设置使用内容坐标，调用方负责约束原生窗口。 */
internal fun setNativePopupBounds(popup: JBPopup, bounds: Rectangle) {
    val insets = popup.content.insets
    popup.size = Dimension(
        (bounds.width - insets.left - insets.right).coerceAtLeast(1),
        (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
    popup.setLocation(Point(bounds.x + insets.left, bounds.y + insets.top))
}
