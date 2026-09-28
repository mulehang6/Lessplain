package com.master.understanding.diagram

import com.intellij.ide.setToolTipText
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.UIManager

/** Compact controls painted in the same Swing layer as the graph. */
internal data class DiagramHierarchyItem(
    val title: String,
    val depth: Int,
    val targetIndex: Int,
    val current: Boolean,
)

internal class UnderstandingDiagramControls(private val diagram: UnderstandingDiagram) : JPanel(null) {
    private val zoomIn = button(GraphControlIcon(IconKind.ZOOM_IN), "放大") { diagram.zoom *= 1.2 }
    private val zoomOut = button(GraphControlIcon(IconKind.ZOOM_OUT), "缩小") { diagram.zoom /= 1.2 }
    private val fit = button(GraphControlIcon(IconKind.FIT), "显示全图") { diagram.fitContent() }
    private val arrangeNodes = button(GraphControlIcon(IconKind.ARRANGE), "一键整理节点") { diagram.arrangeNodes() }
    private val flow = button(GraphControlIcon(IconKind.PAUSE), "暂停路径流动") {
        diagram.flowEnabled = !diagram.flowEnabled
    }
    private val pin = JToggleButton(GraphControlIcon(IconKind.PIN)).apply {
        setToolTipText(HtmlChunk.text("停止自动跳转源码"))
        getAccessibleContext().accessibleName = "停止自动跳转源码"
        preferredSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
        addActionListener { diagram.navigationPinned = isSelected }
    }
    private val follow = JToggleButton(GraphControlIcon(IconKind.FOLLOW)).apply {
        setToolTipText(HtmlChunk.text("停止自动跟随节点"))
        getAccessibleContext().accessibleName = "停止自动跟随节点"
        preferredSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
        addActionListener { diagram.autoFollowNode = !isSelected }
    }
    private val clusterNav = button(GraphControlIcon(IconKind.CLUSTERS), "跳转到追问") { showClusterMenu() }
    private val hierarchy = button(GraphControlIcon(IconKind.HIERARCHY), "查看层级") { showHierarchyMenu() }
    private var hierarchyItems = emptyList<DiagramHierarchyItem>()
    private var onNavigateHierarchy: ((Int) -> Unit)? = null
    val previousButton = button(GraphControlIcon(IconKind.PREVIOUS), "上一步") { diagram.previousStep() }
    val nextButton = button(GraphControlIcon(IconKind.NEXT), "下一步") { diagram.nextStep() }
    private val buttons: List<AbstractButton> = listOf(zoomIn, zoomOut, fit, arrangeNodes, flow, pin, follow) +
        (if (diagram.clusters.isEmpty()) emptyList() else listOf(clusterNav)) + hierarchy

    init {
        isOpaque = false
        buttons.forEach(::add)
        hierarchy.isVisible = false
        previousButton.isVisible = false
        nextButton.isVisible = false
    }

    fun updateFlow(enabled: Boolean) {
        flow.icon = GraphControlIcon(if (enabled) IconKind.PAUSE else IconKind.PLAY)
        val label = if (enabled) "暂停路径流动" else "播放路径流动"
        flow.setToolTipText(HtmlChunk.text(label))
        flow.getAccessibleContext().accessibleName = label
        flow.repaint()
    }

    fun updatePinned(pinned: Boolean) {
        pin.icon = GraphControlIcon(if (pinned) IconKind.PINNED else IconKind.PIN)
        val label = if (pinned) "恢复自动跳转源码" else "停止自动跳转源码"
        pin.setToolTipText(HtmlChunk.text(label))
        pin.getAccessibleContext().accessibleName = label
        pin.isSelected = pinned
        pin.repaint()
    }

    fun updateAutoFollow(enabled: Boolean) {
        follow.icon = GraphControlIcon(if (enabled) IconKind.FOLLOW else IconKind.FOLLOW_OFF)
        val label = if (enabled) "停止自动跟随节点" else "恢复自动跟随节点"
        follow.setToolTipText(HtmlChunk.text(label))
        follow.getAccessibleContext().accessibleName = label
        follow.isSelected = !enabled
        follow.repaint()
    }

    fun updateSteps(hasPrevious: Boolean, hasNext: Boolean) {
        previousButton.isVisible = hasPrevious
        nextButton.isVisible = hasNext
        arrangeSteps(diagram.width, diagram.height)
        diagram.repaint()
    }

    fun updateStepAvailability(hasPrevious: Boolean, hasNext: Boolean) {
        previousButton.isEnabled = previousButton.isVisible && hasPrevious
        nextButton.isEnabled = nextButton.isVisible && hasNext
    }

    fun setHierarchy(items: List<DiagramHierarchyItem>, navigate: (Int) -> Unit) {
        hierarchyItems = items
        onNavigateHierarchy = navigate
        hierarchy.isVisible = items.size > 1
        arrange(diagram.width)
    }

    private fun showHierarchyMenu() {
        JPopupMenu().apply {
            hierarchyItems.forEach { item ->
                add(JMenuItem("${"  ".repeat(item.depth)}${if (item.current) "✓" else if (item.depth == 0) "理解图" else "└"} ${item.title}").apply {
                    setToolTipText(HtmlChunk.text(item.title))
                    addActionListener { onNavigateHierarchy?.invoke(item.targetIndex) }
                })
            }
            show(hierarchy, 0, hierarchy.height)
        }
    }

    fun arrange(availableWidth: Int) {
        val visible = buttons.filter { it.isVisible }
        val columns = when {
            availableWidth >= 204 -> minOf(5, visible.size)
            availableWidth >= 96 -> 2
            else -> 1
        }
        val rows = (visible.size + columns - 1) / columns
        setBounds(12, 12, columns * BUTTON_SIZE + (columns - 1) * GAP, rows * BUTTON_SIZE + (rows - 1) * GAP)
        visible.forEachIndexed { index, button ->
            button.setBounds((index % columns) * (BUTTON_SIZE + GAP), (index / columns) * (BUTTON_SIZE + GAP),
                BUTTON_SIZE, BUTTON_SIZE)
        }
    }

    fun arrangeSteps(availableWidth: Int, availableHeight: Int) {
        val bottom = (availableHeight - INSET - BUTTON_SIZE).coerceAtLeast(0)
        if (availableWidth >= INSET * 2 + BUTTON_SIZE * 2 + GAP) {
            previousButton.setBounds(INSET, bottom, BUTTON_SIZE, BUTTON_SIZE)
            nextButton.setBounds(INSET + BUTTON_SIZE + GAP, bottom, BUTTON_SIZE, BUTTON_SIZE)
        } else {
            previousButton.setBounds(INSET, (bottom - BUTTON_SIZE - GAP).coerceAtLeast(0), BUTTON_SIZE, BUTTON_SIZE)
            nextButton.setBounds(INSET, bottom, BUTTON_SIZE, BUTTON_SIZE)
        }
    }

    private fun showClusterMenu() {
        JPopupMenu().apply {
            diagram.clusters.forEachIndexed { index, cluster ->
                val title = cluster.title.replace('\n', ' ')
                val shortTitle = if (title.length > 36) title.take(36) + "…" else title
                add(JMenuItem("${index + 1}. $shortTitle").apply {
                    setToolTipText(HtmlChunk.text(title))
                    addActionListener { diagram.focusCluster(cluster.id) }
                })
            }
            show(clusterNav, 0, clusterNav.height)
        }
    }

    private fun button(icon: Icon, label: String, action: () -> Unit): JButton = JButton(icon).apply {
        setToolTipText(HtmlChunk.text(label))
        getAccessibleContext().accessibleName = label
        preferredSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
        addActionListener { action() }
    }

    companion object {
        private const val BUTTON_SIZE = 32
        private const val GAP = 4
        private const val INSET = 12
    }
}

private enum class IconKind { ZOOM_IN, ZOOM_OUT, FIT, ARRANGE, PAUSE, PLAY, PIN, PINNED, FOLLOW, FOLLOW_OFF, CLUSTERS, HIERARCHY, PREVIOUS, NEXT }

/** Simple vector glyphs so the controls remain visible in IDE themes and preview rendering. */
private class GraphControlIcon(private val kind: IconKind) : Icon {
    override fun getIconWidth(): Int = 16
    override fun getIconHeight(): Int = 16

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UIManager.getColor("Button.foreground") ?: component?.foreground ?: JBColor.foreground()
            g.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            when (kind) {
                IconKind.ZOOM_IN, IconKind.ZOOM_OUT -> {
                    g.drawOval(1, 1, 10, 10)
                    g.drawLine(10, 10, 15, 15)
                    g.drawLine(4, 6, 8, 6)
                    if (kind == IconKind.ZOOM_IN) g.drawLine(6, 4, 6, 8)
                }
                IconKind.FIT -> {
                    g.drawLine(1, 5, 1, 1)
                    g.drawLine(1, 1, 5, 1)
                    g.drawLine(11, 1, 15, 1)
                    g.drawLine(15, 1, 15, 5)
                    g.drawLine(1, 11, 1, 15)
                    g.drawLine(1, 15, 5, 15)
                    g.drawLine(11, 15, 15, 15)
                    g.drawLine(15, 11, 15, 15)
                }
                IconKind.PAUSE -> {
                    g.fillRoundRect(3, 2, 3, 12, 1, 1)
                    g.fillRoundRect(10, 2, 3, 12, 1, 1)
                }
                IconKind.PLAY -> {
                    val triangle = Path2D.Double().apply {
                        moveTo(4.0, 2.0)
                        lineTo(13.0, 8.0)
                        lineTo(4.0, 14.0)
                        closePath()
                    }
                    g.fill(triangle)
                }
                IconKind.PIN, IconKind.PINNED -> {
                    if (kind == IconKind.PINNED) g.fillRoundRect(4, 2, 8, 5, 2, 2)
                    else g.drawRoundRect(4, 2, 8, 5, 2, 2)
                    g.drawLine(3, 7, 13, 7)
                    g.drawLine(8, 7, 8, 15)
                }
                IconKind.FOLLOW, IconKind.FOLLOW_OFF -> {
                    g.drawOval(3, 3, 10, 10)
                    g.fillOval(6, 6, 4, 4)
                    g.drawLine(8, 0, 8, 2)
                    g.drawLine(8, 14, 8, 16)
                    g.drawLine(0, 8, 2, 8)
                    g.drawLine(14, 8, 16, 8)
                    if (kind == IconKind.FOLLOW_OFF) g.drawLine(1, 15, 15, 1)
                }
                IconKind.CLUSTERS -> {
                    g.drawRoundRect(1, 2, 14, 4, 2, 2)
                    g.drawRoundRect(1, 10, 14, 4, 2, 2)
                }
                IconKind.ARRANGE -> {
                    g.drawRoundRect(1, 2, 5, 4, 1, 1)
                    g.drawRoundRect(10, 2, 5, 4, 1, 1)
                    g.drawRoundRect(5, 10, 6, 4, 1, 1)
                    g.drawLine(8, 6, 8, 10)
                }
                IconKind.HIERARCHY -> {
                    g.drawLine(3, 2, 3, 13)
                    g.drawLine(3, 5, 8, 5)
                    g.drawLine(3, 12, 8, 12)
                    g.fillOval(9, 3, 4, 4)
                    g.fillOval(9, 10, 4, 4)
                }
                IconKind.PREVIOUS, IconKind.NEXT -> {
                    val direction = if (kind == IconKind.PREVIOUS) -1 else 1
                    val center = 8
                    g.drawLine(center - 5 * direction, 8, center + 5 * direction, 8)
                    g.drawLine(center + 5 * direction, 8, center * 1 + direction, 4)
                    g.drawLine(center + 5 * direction, 8, center * 1 + direction, 12)
                }
            }
        } finally {
            g.dispose()
        }
    }
}
