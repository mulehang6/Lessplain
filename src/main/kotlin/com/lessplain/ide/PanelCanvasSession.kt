package com.lessplain.ide

import com.google.gson.Gson
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.lessplain.model.UnderstandingRecord
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.ui.FollowUpLayer

/** 只保存工具窗口的逻辑路由，节点/路径/视角继续由 GraphViewStateStore 管理。 */
internal data class PanelCanvasRoute(val rootId: String, val layers: List<FollowUpLayer> = emptyList())
internal class PanelCanvasSession(private val read: () -> String?, private val write: (String?) -> Unit) {
    constructor(project: Project) : this(
        { PropertiesComponent.getInstance(project).getValue("Lessplain.Panel.LastCanvas") },
        { PropertiesComponent.getInstance(project).setValue("Lessplain.Panel.LastCanvas", it) },
    )
    private val gson = Gson()
    fun load(): PanelCanvasRoute? = runCatching { read()?.let { gson.fromJson(it, PanelCanvasRoute::class.java) } }.getOrNull()
    fun remember(route: PanelCanvasRoute) { if (load() != route) write(gson.toJson(route)) }
    fun removeDeleted(ids: Set<String>) { load()?.takeIf { it.rootId in ids || it.layers.any { layer -> layer.recordId in ids } }?.let { write(null) } }
    fun clear() = write(null)
}

/** 在后台验证整条路由，不按失效的节点或列表下标猜测替代目标。 */
internal fun loadPanelCanvas(repository: UnderstandingRepository, route: PanelCanvasRoute): List<UnderstandingRecord> {
    val root = repository.getRecord(route.rootId)
    require(root.parentId == null) { "上次画布的根记录已失效" }
    var parent = root.id
    return listOf(root) + route.layers.mapIndexed { index, layer ->
        val record = repository.getRecord(layer.recordId)
        require(if (index == 0) record.id == root.id else record.parentId == parent) { "上次的追问层级已变化" }
        require(record.graph.nodes.any { it.id == layer.nodeId }) { "上次的节点已删除" }
        parent = record.id
        record
    }
}
