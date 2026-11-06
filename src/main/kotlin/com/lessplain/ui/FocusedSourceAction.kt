package com.lessplain.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.lessplain.core.LatestBackgroundRead
import com.lessplain.ide.UnderstandingService
import java.awt.BorderLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** 编辑器侧的聚焦目录入口，独立管理展开状态，不操作原生 Project 树。 */
class FocusedSourceAction : AnAction("源码位置", "保留当前文件的父目录和同级邻居", AllIcons.Nodes.Folder), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val panel = FocusedSourcePanel(project)
        val popup = nativePopupBuilder(panel, panel.tree, "源码位置", cancelOnOutside = false)
            .setResizable(true).setDimensionServiceKey(project, "Lessplain.FocusedSource", false).createPopup()
        Disposer.register(popup, panel)
        popup.showCenteredInCurrentWindow(project)
    }
}

/** VFS 快照在后台构建，迟到读取由共用加载器丢弃；关闭时释放编辑器事件订阅。 */
internal class FocusedSourcePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val lifetime = Disposer.newDisposable("Lessplain focused source")
    private val rootPath = project.basePath.orEmpty().replace('\\', '/').trimEnd('/')
    private val editors = FileEditorManager.getInstance(project)
    private val history = DirectoryHistory(editors.selectedFiles.firstOrNull()?.path?.takeIf(::inside) ?: rootPath)
    val tree = JTree(DefaultMutableTreeNode(project.name))
    private val scroll = JBScrollPane(tree)
    private val status = JLabel()
    private val back = JButton("返回")
    private val forward = JButton("前进")
    private var disposed = false
    private val loader = LatestBackgroundRead<FocusedDirectoryNode>(project.getService(UnderstandingService::class.java)::readBackground, ::publish)

    init {
        preferredSize = JBUI.size(430, 540)
        border = JBUI.Borders.empty(8)
        add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEADING)).apply { add(back); add(forward) }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        tree.cellRenderer = object : javax.swing.tree.DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): java.awt.Component {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                val entry = (value as? DefaultMutableTreeNode)?.userObject as? DirectoryEntry
                if (entry != null) {
                    text = entry.name
                    icon = if (entry.directory) AllIcons.Nodes.Folder else AllIcons.FileTypes.Any_type
                    toolTipText = entry.path.removePrefix("$rootPath/")
                }
                return this
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1 || event.clickCount != 1) return
                tree.getPathForLocation(event.x, event.y)?.let { tree.selectionPath = it; openSelected() }
            }
        })
        tree.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "openSource")
        tree.actionMap.put("openSource", object : AbstractAction() { override fun actionPerformed(event: java.awt.event.ActionEvent) = openSelected() })
        back.addActionListener { travel(-1) }
        forward.addActionListener { travel(1) }
        project.messageBus.connect(lifetime).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.path?.takeIf(::inside)?.let { visit(it, open = false) }
            }
        })
        refresh()
    }

    private fun inside(path: String) = rootPath.isNotEmpty() && (path == rootPath || path.startsWith("$rootPath/"))

    private fun remember() { history.remember(DirectoryViewport(scroll.viewport.viewPosition.x, scroll.viewport.viewPosition.y)) }

    private fun openSelected() {
        val entry = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DirectoryEntry ?: return
        visit(entry.path, open = true)
    }

    private fun visit(path: String, open: Boolean) {
        if (disposed || !inside(path)) return
        if (path != history.current) { remember(); history.visit(path); refresh() }
        if (open) openFile(path)
    }

    private fun travel(delta: Int) {
        remember()
        history.travel(delta)
        refresh()
        openFile(history.current)
    }

    private fun openFile(path: String) {
        LocalFileSystem.getInstance().findFileByPath(path)?.takeIf { !it.isDirectory && it.isValid }?.let { editors.openFile(it, true) }
    }

    private fun refresh() {
        val current = history.current
        back.isEnabled = history.canBack
        forward.isEnabled = history.canForward
        status.text = current.removePrefix("$rootPath/")
        loader.request {
            ApplicationManager.getApplication().runReadAction<FocusedDirectoryNode> {
                focusedDirectory(DirectoryEntry(rootPath, project.name, true), current) { path ->
                    LocalFileSystem.getInstance().findFileByPath(path)?.children.orEmpty()
                        .filterNot { it.name in setOf(".git", ".gradle", "build", "node_modules") }
                        .map { DirectoryEntry(it.path, it.name, it.isDirectory) }
                }
            }
        }
    }

    private fun publish(result: Result<FocusedDirectoryNode>) {
        if (disposed) return
        result.onFailure { status.text = "目录无法读取：${it.message}" }.onSuccess { snapshot ->
            var target: TreePath? = null
            val expand = mutableListOf<TreePath>()
            fun node(value: FocusedDirectoryNode): DefaultMutableTreeNode {
                val result = DefaultMutableTreeNode(value.entry)
                value.children.forEach { result.add(node(it)) }
                return result
            }
            val root = node(snapshot)
            tree.model = DefaultTreeModel(root)
            val entries = root.depthFirstEnumeration()
            while (entries.hasMoreElements()) {
                val item = entries.nextElement() as DefaultMutableTreeNode
                val entry = item.userObject as DirectoryEntry
                val path = TreePath(item.path)
                if (history.current == entry.path) target = path
                if (item.childCount > 0) expand += path
            }
            expand.sortedBy { it.pathCount }.forEach(tree::expandPath)
            tree.selectionPath = target
            val current = history.current
            val position = history.viewport()
            val selected = target
            javax.swing.SwingUtilities.invokeLater {
                if (!disposed && history.current == current) {
                    if (position != null) scroll.viewport.viewPosition = Point(position.x, position.y)
                    else selected?.let(tree::scrollPathToVisible)
                }
            }
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        loader.close()
        Disposer.dispose(lifetime)
        removeAll()
    }
}
