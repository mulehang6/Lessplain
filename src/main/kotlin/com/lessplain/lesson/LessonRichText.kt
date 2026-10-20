package com.lessplain.lesson

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.ui.JBColor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/** StyledDocument 与批注共用同一串纯文本，字体和链接都不会改变选区偏移。 */
internal fun lessonTextView(block: LessonBlock): JTextPane = object : JTextPane() {
    override fun getPreferredSize(): Dimension {
        val owner = parent
        val width = (owner?.let { it.width - it.insets.left - it.insets.right } ?: 680).coerceAtLeast(40)
        setSize(width, Short.MAX_VALUE.toInt())
        return super.getPreferredSize().apply { this.width = width }
    }
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    override fun getMinimumSize(): Dimension = Dimension(40, preferredSize.height)
}.apply {
    isEditable = false
    isOpaque = false
    border = JBUI.Borders.empty()
    val base = UIUtil.getLabelFont()
    font = base.deriveFont(base.size2D + 1)
    foreground = UIUtil.getLabelForeground()
    val document = styledDocument
    val paragraph = SimpleAttributeSet().apply {
        StyleConstants.setLineSpacing(this, 0.22f)
        StyleConstants.setSpaceAbove(this, 2f)
        StyleConstants.setSpaceBelow(this, 8f)
    }
    val runs = block.richText ?: listOf(LessonTextRun(block.text))
    runs.forEach { run ->
        val attributes = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, base.family)
            StyleConstants.setFontSize(this, base.size + 1)
            if (block.level > 0) {
                StyleConstants.setBold(this, true)
                StyleConstants.setFontSize(this, base.size + when (block.level) { 1 -> 8; 2 -> 5; else -> 2 })
            }
            when (run.style) {
                "strong" -> StyleConstants.setBold(this, true)
                "emphasis" -> StyleConstants.setItalic(this, true)
                "code" -> {
                    val scheme = ApplicationManager.getApplication()?.let { EditorColorsManager.getInstance().globalScheme }
                    StyleConstants.setFontFamily(this, scheme?.editorFontName ?: Font.MONOSPACED)
                    scheme?.let { StyleConstants.setFontSize(this, it.editorFontSize) }
                    StyleConstants.setBackground(this, UIUtil.getTextFieldBackground())
                }
                "link" -> {
                    StyleConstants.setUnderline(this, true)
                    StyleConstants.setForeground(this, JBColor.namedColor("Link.activeForeground", UIUtil.getLabelForeground()))
                    addAttribute("lesson.href", run.href)
                }
            }
        }
        document.insertString(document.length, run.text, attributes)
    }
    document.setParagraphAttributes(0, document.length, paragraph, false)
    caretPosition = 0
    addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            if (event.button != MouseEvent.BUTTON1 || selectionStart != selectionEnd) return
            val href = document.getCharacterElement(viewToModel2D(event.point).coerceAtLeast(0))
                .attributes.getAttribute("lesson.href") as? String ?: return
            if (safeLessonLink(href)) BrowserUtil.browse(href)
        }
    })
}

internal fun selectableLessonText(value: String, level: Int = 0): JTextPane =
    lessonTextView(LessonBlock("display", "text", text = value, level = level))
