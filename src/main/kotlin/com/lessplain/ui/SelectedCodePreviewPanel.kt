package com.lessplain.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.lessplain.model.SourceAnchor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** 预览只依赖问题自己的冻结锚点，不读取当前源码文件。 */
internal data class SelectedCodePreview(
    val project: Project,
    val anchor: SourceAnchor? = null,
    val text: String = anchor?.text.orEmpty(),
    val fileName: String = anchor?.path.orEmpty(),
)

internal fun questionEditorTitle(nodeLabel: String): String =
    "提问 · ${nodeLabel.replace('\r', ' ').replace('\n', ' ')}"

/** 用平台只读编辑器展示完整快照，展开只改变视口，不改变内容或草稿。 */
internal class SelectedCodePreviewPanel(
    preview: SelectedCodePreview,
    private val changed: () -> Unit,
) : JPanel(BorderLayout()), AutoCloseable {
    private val factory = EditorFactory.getInstance()
    private val lifetime: Disposable = Disposer.newDisposable("Lessplain code preview")
    private val document = factory.createDocument(preview.text.replace("\r\n", "\n").replace('\r', '\n'))
    internal var expanded = false
        private set
    internal val toggle = CodePreviewToggle { expanded }.apply {
        isVisible = document.lineCount > COLLAPSED_ROWS
        addActionListener {
            expanded = !expanded
            text = if (expanded) "收起" else "展开"
            accessibleContext.accessibleName = if (expanded) "收起代码片段" else "展开代码片段"
            if (!expanded) editor.scrollingModel.scrollVertically(0)
            changed()
        }
    }
    private val footer = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
        isOpaque = false
        isVisible = toggle.isVisible
        add(toggle)
    }
    private var closed = false
    private val fonts = CanvasUiFonts(listOf(toggle), onRefreshed = ::metricsChanged)
    internal val editor: Editor = try {
        factory.createEditor(document, preview.project,
            FileTypeManager.getInstance().getFileTypeByFileName(preview.fileName.substringAfterLast('/').substringAfterLast('\\')),
            true)
    } catch (failure: Throwable) {
        fonts.close()
        Disposer.dispose(lifetime)
        throw failure
    }

    init {
        check(SwingUtilities.isEventDispatchThread())
        try {
            isOpaque = false
            border = CodePreviewBorder()
            toggle.foreground = UnderstandingUiStyle.muted
            editor.settings.apply {
                isLineNumbersShown = true
                isLineMarkerAreaShown = false
                isFoldingOutlineShown = false
                isRightMarginShown = false
                isUseSoftWraps = false
                isAdditionalPageAtBottom = false
                additionalLinesCount = 0
                additionalColumnsCount = 0
                isVirtualSpace = false
            }
            editor.contentComponent.accessibleContext.accessibleName = "代码片段，只读"
            add(editor.component, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
            ApplicationManager.getApplication().messageBus.connect(lifetime)
                .subscribe(EditorColorsManager.TOPIC, EditorColorsListener { metricsChanged() })
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    /** 先由提问弹层预留输入和按钮空间，再在剩余高度内分配代码视口。 */
    fun heightFor(width: Int, availableHeight: Int): Int {
        val budget = availableHeight.coerceAtLeast(0)
        val frameInsets = insets
        val frameHeight = frameInsets.top + frameInsets.bottom
        val toggleHeight = if (footer.isVisible) footer.preferredSize.height + (layout as BorderLayout).vgap else 0
        val rows = minOf(editor.document.lineCount, if (expanded) EXPANDED_ROWS else COLLAPSED_ROWS)
        val editorWidth = (width - frameInsets.left - frameInsets.right).coerceAtLeast(1)
        val textHeight = rows * editor.lineHeight
        editor.component.setSize(editorWidth, textHeight)
        layoutEditor(editor.component as Container)
        val chromeHeight = (textHeight - editor.scrollingModel.visibleArea.height).coerceAtLeast(0)
        val codeHeight = (textHeight + chromeHeight).coerceAtMost((budget - frameHeight - toggleHeight).coerceAtLeast(0))
        editor.component.preferredSize = Dimension(editorWidth, codeHeight)
        editor.component.setSize(editorWidth, codeHeight)
        layoutEditor(editor.component as Container)
        preferredSize = Dimension(width.coerceAtLeast(1), minOf(budget, frameHeight + codeHeight + toggleHeight))
        return preferredSize.height
    }

    /** 滚动条尺寸由 IDE 布局决定，不能用固定像素挤占或多露一行。 */
    private fun layoutEditor(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutEditor)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = editor.colorsScheme.defaultBackground
            g.fillRoundRect(0, 0, width, height, UnderstandingUiStyle.NODE_ARC, UnderstandingUiStyle.NODE_ARC)
        } finally { g.dispose() }
        super.paintComponent(graphics)
    }

    private fun metricsChanged() {
        if (closed) return
        toggle.foreground = UnderstandingUiStyle.muted
        repaint()
        changed()
    }

    override fun close() {
        if (closed) return
        check(SwingUtilities.isEventDispatchThread())
        closed = true
        fonts.close()
        Disposer.dispose(lifetime)
        factory.releaseEditor(editor)
    }

    private companion object {
        const val COLLAPSED_ROWS = 6
        const val EXPANDED_ROWS = 20
    }
}
