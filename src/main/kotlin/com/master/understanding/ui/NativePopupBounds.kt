package com.master.understanding.ui

import com.intellij.openapi.ui.popup.JBPopup
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Point

/** JBPopup reports its content origin; clamping requires the whole native window origin. */
internal fun nativePopupLocation(popup: JBPopup): Point {
    val location = popup.locationOnScreen
    val insets = popup.content.insets
    return Point(location.x - insets.left, location.y - insets.top)
}

/** Public size/location setters use content coordinates; callers constrain the native window. */
internal fun setNativePopupBounds(popup: JBPopup, bounds: Rectangle) {
    val insets = popup.content.insets
    popup.size = Dimension(
        (bounds.width - insets.left - insets.right).coerceAtLeast(1),
        (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
    )
    popup.setLocation(Point(bounds.x + insets.left, bounds.y + insets.top))
}
