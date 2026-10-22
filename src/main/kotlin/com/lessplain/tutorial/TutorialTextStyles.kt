package com.lessplain.tutorial

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/** 使用 IDE 当前配色，整段词法分析保留多行 token 的上下文。 */
internal fun styleTutorialCode(document: StyledDocument, start: Int, code: String, language: String, editor: Editor) {
    val type = FileTypeManager.getInstance().getFileTypeByExtension(if (language == "kotlin") "kt" else language)
    val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(type, editor.project, null) ?: return
    val lexer = highlighter.highlightingLexer
    lexer.start(code)
    while (lexer.tokenType != null) {
        val color = highlighter.getTokenHighlights(requireNotNull(lexer.tokenType)).mapNotNull { editor.colorsScheme.getAttributes(it)?.foregroundColor }.lastOrNull()
        if (color != null) document.setCharacterAttributes(start + lexer.tokenStart, lexer.tokenEnd - lexer.tokenStart, SimpleAttributeSet().apply { StyleConstants.setForeground(this, color) }, false)
        lexer.advance()
    }
}

/** 稳定 ID 决定笔迹形状，重复绘制不产生随机抖动。 */
internal fun paintInk(g: Graphics2D, bounds: Rectangle, kind: String, seed: Int) {
    val x = bounds.x.toDouble(); val y = bounds.y.toDouble(); val w = bounds.width.toDouble(); val h = bounds.height.toDouble()
    val wobble = (seed and 3) * 0.4 + 0.8
    val path = java.awt.geom.Path2D.Double().apply {
        moveTo(x + 4, y + wobble)
        curveTo(x + w / 3, y - wobble, x + w * 0.8, y + wobble, x + w - 2, y)
        quadTo(x + w + 2, y + h / 2, x + w - 3, y + h)
        curveTo(x + w * 0.7, y + h - wobble, x + w / 4, y + h + wobble, x + 2, y + h)
        quadTo(x - 2, y + h / 2, x + 4, y + wobble)
        closePath()
    }
    g.color = when (kind) {
        "note" -> Color(245, 205, 80, 65)
        "both" -> Color(175, 135, 235, 75)
        else -> Color(90, 170, 245, 65)
    }
    g.fill(path)
}
