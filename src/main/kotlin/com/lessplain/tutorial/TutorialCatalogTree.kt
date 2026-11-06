package com.lessplain.tutorial

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.installNativeMenu
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** 可编辑的阅读目录；源码目录与问答层级不进入此树。 */
internal class TutorialCatalogTree(private val service: TutorialService, private val opened: () -> Unit) : JPanel(BorderLayout()) {
    private data class Row(val item: CatalogItem, val record: TutorialRecord?) {
        override fun toString(): String = record?.title ?: item.title
    }
    private val root = DefaultMutableTreeNode("教程")
    val tree = JTree(DefaultTreeModel(root)).apply { isRootVisible = false; showsRootHandles = true }
    private val catalog get() = service.catalog

    init {
        add(JBScrollPane(tree), BorderLayout.CENTER)
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON3) tree.selectionPath = tree.getPathForLocation(event.x, event.y)
            }
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1) {
                    val path = tree.getPathForLocation(event.x, event.y) ?: return
                    val row = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Row ?: return
                    if (row.record != null) open(row)
                    else if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                }
            }
        })
        tree.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "open")
        tree.actionMap.put("open", object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) = openSelection()
        })
        installNativeMenu(tree) {
            val row = selected()
            val folder = row?.item?.takeIf { it.tutorialId == null }
            val writable = !service.store.readOnly
            listOf(
                NativeMenuItem("新建目录", writable) { createFolder(folder?.id) },
                NativeMenuItem("重命名目录", writable && folder != null) {
                    folder?.let { item -> input("重命名目录", item.title)?.let { title -> change { catalog.rename(item.id, title) } } }
                },
                NativeMenuItem("移动到目录", writable && row != null) { row?.let { relocate(it, copy = false) } },
                NativeMenuItem("添加引用到目录", writable && row?.record != null) { row?.let { relocate(it, copy = true) } },
                NativeMenuItem("上移", writable && row != null && !row.item.id.startsWith("unfiled:")) { row?.let { change { catalog.reorder(it.item.id, -1) } } },
                NativeMenuItem("下移", writable && row != null && !row.item.id.startsWith("unfiled:")) { row?.let { change { catalog.reorder(it.item.id, 1) } } },
                NativeMenuItem("解散目录，保留内容", writable && folder != null) { folder?.let { change { catalog.dissolve(it.id) } } },
                NativeMenuItem("移除此处引用", writable && row?.record != null && !row.item.id.startsWith("unfiled:")) { row?.let { change { catalog.removeReference(it.item.id) } } },
                NativeMenuItem("删除教程", writable && row?.record != null) { row?.record?.let { service.deleteTutorial(it.id) } },
            )
        }
    }

    fun createFolder(parent: String? = null) { input("新建目录", "")?.let { title -> change { catalog.createFolder(parent, title) } } }

    fun moveSelection(delta: Int) {
        if (tree.rowCount == 0) return
        val row = ((tree.selectionRows?.firstOrNull() ?: -1) + delta).coerceIn(0, tree.rowCount - 1)
        tree.setSelectionRow(row)
        tree.scrollRowToVisible(row)
    }

    fun openSelection() {
        val row = selected() ?: return
        if (row.record != null) open(row)
        else tree.selectionPath?.let { if (tree.isExpanded(it)) tree.collapsePath(it) else tree.expandPath(it) }
    }

    private fun input(title: String, initial: String): String? =
        Messages.showInputDialog(this, "目录名称", title, null, initial, null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun selected() = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Row

    private fun open(row: Row) { row.record?.let { service.open(it.id); opened() } }

    private fun change(action: () -> Unit) = service.attempt { action(); service.changed("catalog") }

    private fun relocate(row: Row, copy: Boolean) {
        val choices = mutableListOf(FolderChoice(null, "目录根层"))
        val folders = catalog.snapshot().filter { it.tutorialId == null }
        val byId = folders.associateBy { it.id }
        for (folder in folders) {
            val ancestors = generateSequence(folder) { it.parentId?.let(byId::get) }.toList()
            if (ancestors.any { it.id == row.item.id }) continue
            choices += FolderChoice(folder.id, ancestors.asReversed().joinToString(" / ") { it.title })
        }
        val dialog = FolderDestinationDialog(service.project, choices)
        if (!dialog.showAndGet()) return
        change {
            val parent = dialog.destination()
            if (copy || row.item.id.startsWith("unfiled:")) catalog.place(row.record!!.id, row.record.title, parent)
            else catalog.move(row.item.id, parent)
        }
    }

    fun refresh(records: List<TutorialRecord>) {
        val expanded = (0 until tree.rowCount).mapNotNull { index -> tree.getPathForRow(index)?.takeIf(tree::isExpanded) }
            .mapNotNull { ((it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Row)?.item?.id }.toSet()
        val selected = selected()?.item?.id
        val byId = records.associateBy { it.id }
        val items = catalog.snapshot()
        val referenced = items.mapNotNull { it.tutorialId }.toSet()
        root.removeAllChildren()
        fun append(parent: DefaultMutableTreeNode, parentId: String?) {
            for (item in items.filter { it.parentId == parentId }) {
                if (item.tutorialId != null && item.tutorialId !in byId) continue
                val node = DefaultMutableTreeNode(Row(item, item.tutorialId?.let(byId::get)))
                parent.add(node)
                if (item.tutorialId == null) append(node, item.id)
            }
        }
        append(root, null)
        records.filterNot { it.id in referenced }.sortedByDescending { it.createdAt }.forEach {
            root.add(DefaultMutableTreeNode(Row(CatalogItem("unfiled:${it.id}", null, it.title, it.id), it)))
        }
        (tree.model as DefaultTreeModel).reload()
        val nodes = root.depthFirstEnumeration()
        while (nodes.hasMoreElements()) {
            val node = nodes.nextElement() as DefaultMutableTreeNode
            val row = node.userObject as? Row ?: continue
            val path = TreePath(node.path)
            if (row.item.id in expanded) tree.expandPath(path)
            if (row.item.id == selected) tree.selectionPath = path
        }
    }
}

private data class FolderChoice(val id: String?, val title: String) { override fun toString() = title }

private class FolderDestinationDialog(project: com.intellij.openapi.project.Project, choices: List<FolderChoice>) : DialogWrapper(project) {
    private val folders = ComboBox(choices.toTypedArray())
    init { title = "选择目标目录"; init() }
    override fun createCenterPanel(): JComponent = folders
    fun destination(): String? = (folders.selectedItem as FolderChoice).id
}
