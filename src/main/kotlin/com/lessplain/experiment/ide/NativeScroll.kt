package com.lessplain.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType

/** 原生编辑器使用自身的滚动设置，不再依赖画布动画。 */
internal class SourceScrollAnimation : Disposable {
    fun moveTo(editor: Editor, offset: Int) {
        if (editor.isDisposed) return
        editor.selectionModel.removeSelection()
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }
    fun cancel() = Unit
    override fun dispose() = cancel()
}
