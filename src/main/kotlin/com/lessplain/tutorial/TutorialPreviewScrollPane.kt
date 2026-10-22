package com.lessplain.tutorial

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.ui.components.JBScrollPane
import com.lessplain.ide.uiRead
import java.awt.event.MouseWheelEvent

/** 正文不安装滚轮监听器，由 AWT 转给本容器；图框内仍由共享画布处理。 */
internal class TutorialPreviewScrollPane(private val preview: TutorialPreviewPane, private val editor: Editor) : JBScrollPane(preview) {
    override fun processMouseWheelEvent(event: MouseWheelEvent) {
        if (event.isControlDown && !event.isConsumed) {
            (editor as? EditorEx)?.setFontSize((editor.colorsScheme.editorFontSize - event.wheelRotation).coerceIn(8, 48))
            uiRead { preview.refresh() }
            event.consume()
        } else super.processMouseWheelEvent(event)
    }
}
