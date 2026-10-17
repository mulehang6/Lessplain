package com.lessplain.experiment.browser

import java.awt.Cursor

/** 前端只上报语义变化；EDT 将它映射到当前 JCEF 内容控件。 */
internal enum class BrowserCursor(val awtType: Int) {
    DEFAULT(Cursor.DEFAULT_CURSOR),
    POINTER(Cursor.HAND_CURSOR),
    TEXT(Cursor.TEXT_CURSOR),
    GRAB(Cursor.HAND_CURSOR),
    GRABBING(Cursor.MOVE_CURSOR);

    fun awtCursor(): Cursor = Cursor.getPredefinedCursor(awtType)

    companion object {
        fun parse(value: String): BrowserCursor = when (value) {
            "default" -> DEFAULT
            "pointer" -> POINTER
            "text" -> TEXT
            "grab" -> GRAB
            "grabbing" -> GRABBING
            else -> error("光标样式无效")
        }
    }
}
