package com.master.understanding.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayModel
import com.intellij.openapi.editor.ScrollingModel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.lang.reflect.Proxy
import javax.swing.JPanel

/** Minimal editor interfaces exercise actual marker events and resource ownership without starting an IDE. */
internal class SourceMarkerTestEditor {
    val component = JPanel().apply { setSize(800, 600) }
    val text = "    foo()\n    bar()\n"
    val inlayBounds = Rectangle(0, 30, 800, 32)
    var visibleArea = Rectangle(0, 40, 800, 300)
    var inlayDisposed = false
    var highlighterDisposed = false
    val documentListeners = mutableListOf<DocumentListener>()
    lateinit var renderer: InsightInlayRenderer
    private val lineStarts = listOf(0, text.indexOf('\n') + 1, text.length)
    private val font = Font(Font.DIALOG, Font.PLAIN, 12)
    private val document = fake(Document::class.java) { name, args ->
        when (name) {
            "addDocumentListener" -> {
                val listener = args[0] as DocumentListener
                documentListeners += listener
                (args.getOrNull(1) as? Disposable)?.let { parent ->
                    Disposer.register(parent, Disposable { documentListeners -= listener })
                }
                null
            }
            "removeDocumentListener" -> { documentListeners -= args[0] as DocumentListener; null }
            "getTextLength" -> text.length
            "getCharsSequence" -> text
            "getLineNumber" -> lineStarts.indexOfLast { it <= args[0] as Int }
            "getLineStartOffset" -> lineStarts[args[0] as Int]
            "getLineEndOffset" -> text.indexOf('\n', lineStarts[args[0] as Int]).let { if (it < 0) text.length else it }
            else -> null
        }
    }

    fun changeDocument() {
        val event = object : DocumentEvent(document) {
            override fun getOffset(): Int = 0
            override fun getOldLength(): Int = 0
            override fun getNewLength(): Int = 1
            override fun getOldFragment(): CharSequence = ""
            override fun getNewFragment(): CharSequence = "x"
            override fun getOldTimeStamp(): Long = 0L
        }
        documentListeners.toList().forEach { it.documentChanged(event) }
    }
    private val colors = fake(EditorColorsScheme::class.java) { name, _ -> if (name == "getFont") font else null }
    private val scrolling = fake(ScrollingModel::class.java) { name, _ ->
        if (name == "getVisibleArea") visibleArea else null
    }

    val inlay: Inlay<*> = fake(Inlay::class.java) { name, _ ->
        when (name) {
            "getEditor" -> editor
            "getBounds" -> inlayBounds
            "getRenderer" -> renderer
            "isValid" -> !inlayDisposed
            "update" -> { inlayBounds.height = renderer.calcHeightInPixels(inlay); null }
            "dispose" -> { inlayDisposed = true; null }
            else -> null
        }
    }
    private val inlayModel = fake(InlayModel::class.java) { name, args ->
        if (name == "addBlockElement") {
            renderer = args.last() as InsightInlayRenderer
            inlay
        } else null
    }
    private val highlighter = fake(RangeHighlighter::class.java) { name, _ ->
        when (name) {
            "getStartOffset" -> 0
            "getEndOffset" -> text.length
            "dispose" -> { highlighterDisposed = true; null }
            else -> null
        }
    }
    private val markup = fake(MarkupModel::class.java) { name, _ ->
        if (name == "addRangeHighlighter") highlighter else null
    }
    val editor: Editor = fake(Editor::class.java) { name, args ->
        when (name) {
            "getDocument" -> document
            "getContentComponent" -> component
            "getInlayModel" -> inlayModel
            "getMarkupModel" -> markup
            "getColorsScheme" -> colors
            "getScrollingModel" -> scrolling
            "getLineHeight" -> 20
            "offsetToXY" -> {
                val offset = args[0] as Int
                val line = lineStarts.indexOfLast { it <= offset }
                Point(40 + (offset - lineStarts[line]) * 8, inlayBounds.y + inlayBounds.height + line * 20)
            }
            else -> null
        }
    }

    private fun <T> fake(type: Class<T>, handle: (String, List<Any?>) -> Any?): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                "toString" -> "Test${type.simpleName}"
                else -> handle(method.name, arguments?.toList().orEmpty()) ?: when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            }
        } as T
    }
}
