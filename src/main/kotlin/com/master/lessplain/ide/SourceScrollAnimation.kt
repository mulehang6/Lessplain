package com.master.lessplain.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.master.lessplain.diagram.UnderstandingCameraMotion
import java.awt.Point
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.roundToInt

/** 移动已可见的源码编辑器，不重新打开文件或修改全局滚动设置。 */
internal class SourceScrollAnimation : Disposable {
    private var timer: Timer? = null

    fun moveTo(editor: Editor, offset: Int) {
        cancel()
        editor.selectionModel.removeSelection()
        editor.caretModel.moveToOffset(offset)

        val viewport = (editor as? EditorEx)?.scrollPane?.viewport
        if (viewport == null || !viewport.isShowing || !editor.settings.isAnimatedScrolling) {
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            return
        }
        val visibleHeight = viewport.extentSize.height
        val contentHeight = viewport.viewSize.height
        if (visibleHeight <= 0 || contentHeight <= 0) {
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            return
        }
        val startY = viewport.viewPosition.y
        val targetY = (editor.offsetToXY(offset).y + editor.lineHeight / 2 - visibleHeight / 2)
            .coerceIn(0, (contentHeight - visibleHeight).coerceAtLeast(0))
        if (abs(targetY - startY) < editor.lineHeight) {
            viewport.viewPosition = Point(viewport.viewPosition.x, targetY)
            return
        }

        val motion = UnderstandingCameraMotion(0.0, startY.toDouble())
        motion.retarget(0.0, startY.toDouble(), 0.0, targetY.toDouble(), System.nanoTime())
        timer = Timer(16) {
            if (editor.isDisposed || !viewport.isShowing) {
                cancel()
                return@Timer
            }
            val now = System.nanoTime()
            val y = if (motion.isFinished(now)) targetY else motion.positionAt(now).y.roundToInt()
            viewport.viewPosition = Point(viewport.viewPosition.x, y)
            if (motion.isFinished(now)) cancel()
        }.also(Timer::start)
    }

    fun cancel() {
        timer?.stop()
        timer = null
    }

    override fun dispose() = cancel()
}
