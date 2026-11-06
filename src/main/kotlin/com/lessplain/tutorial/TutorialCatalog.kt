package com.lessplain.tutorial

import com.google.gson.Gson
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/** 目录只组织引用，不拥有正文；删除引用或解散目录不会删除教程。 */
internal data class CatalogItem(
    val id: String,
    val parentId: String?,
    val title: String,
    val tutorialId: String? = null,
)

/** 列表顺序就是同级顺序；持久化成功后才替换内存中的目录。 */
internal class TutorialCatalog(private val read: () -> String?, private val write: (String) -> Unit) {
    private val gson = Gson()
    private var items = read()?.let { gson.fromJson(it, Array<CatalogItem>::class.java)?.toList() }.orEmpty().also(::validate)

    constructor(project: Project) : this(
        { PropertiesComponent.getInstance(project).getValue(KEY) },
        { PropertiesComponent.getInstance(project).setValue(KEY, it) },
    )

    fun snapshot(): List<CatalogItem> = items.toList()

    fun children(parentId: String?): List<CatalogItem> = items.filter { it.parentId == parentId }

    fun createFolder(parentId: String?, title: String): CatalogItem {
        val item = CatalogItem(newId("folder"), parentId, title.trim())
        commit(items + item)
        return item
    }

    fun rename(id: String, title: String) {
        val item = item(id)
        tutorialRequire(item.tutorialId == null, "INVALID_KIND", "id", "只能在目录中重命名文件夹")
        commit(items.map { if (it.id == id) it.copy(title = title.trim()) else it })
    }

    fun move(id: String, parentId: String?) {
        item(id)
        commit(items.map { if (it.id == id) it.copy(parentId = parentId) else it })
    }

    fun place(tutorialId: String, title: String, parentId: String?): CatalogItem {
        items.firstOrNull { it.tutorialId == tutorialId && it.parentId == parentId }?.let { return it }
        val item = CatalogItem(newId("entry"), parentId, title, tutorialId)
        commit(items + item)
        return item
    }

    /** 首次生成可建议目录；后续重试保留用户已安排的目录位置。 */
    fun placeNew(tutorialId: String, title: String, path: List<String>) {
        if (path.isEmpty() || items.any { it.tutorialId == tutorialId }) return
        tutorialRequire(path.all { it.isNotBlank() }, "EMPTY_TITLE", "folderPath", "目录名称不能为空")
        var parent: String? = null
        // 整条路径与引用一次提交，避免校验失败留下半条目录。
        val next = items.toMutableList()
        for (name in path) {
            val existing = next.firstOrNull { it.parentId == parent && it.tutorialId == null && it.title == name.trim() }
            val folder = existing ?: CatalogItem(newId("folder"), parent, name.trim()).also(next::add)
            parent = folder.id
        }
        next += CatalogItem(newId("entry"), parent, title, tutorialId)
        commit(next)
    }

    fun reorder(id: String, delta: Int) {
        val item = item(id)
        val siblings = children(item.parentId)
        val current = siblings.indexOf(item)
        val target = (current + delta).coerceIn(0, siblings.lastIndex)
        if (target == current) return
        val next = items.toMutableList()
        val a = next.indexOf(item)
        val b = next.indexOf(siblings[target])
        next[a] = siblings[target]
        next[b] = item
        commit(next)
    }

    fun removeReference(id: String) {
        tutorialRequire(item(id).tutorialId != null, "INVALID_KIND", "id", "此操作仅移除教程引用")
        commit(items.filterNot { it.id == id })
    }

    fun dissolve(id: String) {
        val folder = item(id)
        tutorialRequire(folder.tutorialId == null, "INVALID_KIND", "id", "此操作仅解散目录")
        commit(items.filterNot { it.id == id }.map { if (it.parentId == id) it.copy(parentId = folder.parentId) else it })
    }

    private fun item(id: String) = items.firstOrNull { it.id == id }
        ?: throw TutorialProblem("NOT_FOUND", "id", "目录项不存在")

    private fun commit(next: List<CatalogItem>) {
        validate(next)
        write(gson.toJson(next))
        items = next
    }

    private fun validate(values: List<CatalogItem>) {
        tutorialRequire(values.map { it.id }.distinct().size == values.size, "DUPLICATE_ID", "id", "目录项重复")
        val byId = values.associateBy { it.id }
        for (item in values) {
            tutorialRequire(item.id.isNotBlank() && item.title.isNotBlank(), "EMPTY_TITLE", "title", "目录名称不能为空")
            val visited = mutableSetOf(item.id)
            var parent = item.parentId
            while (parent != null) {
                tutorialRequire(visited.add(parent), "DIRECTORY_CYCLE", "parentId", "不能移入自身或子目录")
                val folder = byId[parent]
                tutorialRequire(folder != null && folder.tutorialId == null, "INVALID_PARENT", "parentId", "目标必须是现有目录")
                parent = folder!!.parentId
            }
        }
        val folders = values.filter { it.tutorialId == null }
        tutorialRequire(folders.map { it.parentId to it.title }.distinct().size == folders.size,
            "DUPLICATE_TITLE", "title", "同级目录名称不能重复")
    }

    companion object { private const val KEY = "lessplain.tutorial.catalog" }
}
