package com.lessplain.experiment.core

import kotlinx.serialization.json.*

/** 分组只组织组件，图端点仍属于各自 Graph；状态选择路径不能串到另一张图。 */
internal object ViewCompositionValidation {
    fun validate(elements: JsonObject, rootId: String? = null) {
        val rootType = rootId?.let { type(elements, it) }
        val structuralIds = elements.filterValues {
            it.jsonObject.getValue("type").jsonPrimitive.content in setOf("Graph", "Cluster", "Board")
        }.keys
        require(rootId == null || structuralIds.isEmpty() || rootType in setOf("Graph", "Cluster", "Board")) {
            "含图页面须以 Board、Cluster 或 Graph 为根；普通布局容器只能放在 companion 中"
        }
        if (rootType == "Graph") {
            require(structuralIds == setOf(rootId)) { "Graph 根不能包含其他图或分组" }
        }
        val selectionPaths = mutableSetOf<String>()
        elements.forEach { (id, raw) ->
            val element = raw.jsonObject
            when (element.getValue("type").jsonPrimitive.content) {
                "Board" -> {
                    require(element["slots"]?.jsonObject.isNullOrEmpty()) { "Board 不接受插槽：$id" }
                    val children = element["children"]?.jsonArray.orEmpty()
                    require(children.isNotEmpty()) { "Board 至少需要一个 Cluster：$id" }
                    children.forEach { child ->
                        require(type(elements, child.jsonPrimitive.content) == "Cluster") { "Board 的直接成员必须是 Cluster：$id" }
                    }
                }
                "Cluster" -> validateCluster(id, element, elements)
                "Graph" -> {
                    require(element["children"]?.jsonArray.isNullOrEmpty() && element["slots"]?.jsonObject.isNullOrEmpty()) {
                        "Graph 只能声明节点与边，其他组件须放在 companion 中：$id"
                    }
                    val path = element.getValue("props").jsonObject["selectionPath"]
                    if (path != null && path !is JsonNull) {
                        require(path is JsonPrimitive && path.isString) { "图选择路径须为固定 JSON Pointer：$id" }
                        require(validPointer(path.content)) { "图选择路径不是有效 JSON Pointer：$id" }
                        require(selectionPaths.add(path.content)) { "不同图不能共用选择路径：${path.content}" }
                    } else {
                        val defaultPath = "/graphs/${id.replace("~", "~0").replace("/", "~1")}/selectedNode"
                        require(selectionPaths.add(defaultPath)) { "图选择路径与另一张图默认路径冲突：$defaultPath" }
                    }
                }
            }
        }
        selectionPaths.forEach { path ->
            require(selectionPaths.none { other -> other != path && path.startsWith("$other/") }) {
                "图选择路径不能嵌套在另一张图的选择值中：$path"
            }
        }
    }

    private fun validateCluster(id: String, element: JsonObject, elements: JsonObject) {
        require(element["children"]?.jsonArray.isNullOrEmpty()) { "Cluster 成员须使用 diagram 或 companion 插槽：$id" }
        val slots = element["slots"]?.jsonObject.orEmpty()
        require(slots.keys.all { it == "diagram" || it == "companion" }) { "Cluster 插槽名称无效：$id" }
        val diagrams = slots["diagram"]?.jsonArray.orEmpty()
        val companions = slots["companion"]?.jsonArray.orEmpty()
        require(diagrams.size <= 1) { "每个 Cluster 至多包含一张图：$id" }
        require(diagrams.isNotEmpty() || companions.isNotEmpty()) { "Cluster 不能为空：$id" }
        diagrams.forEach { graph ->
            require(type(elements, graph.jsonPrimitive.content) == "Graph") { "diagram 插槽必须引用 Graph：$id" }
        }
        fun checkCompanion(childId: String) {
            val child = elements.getValue(childId).jsonObject
            require(type(elements, childId) !in setOf("Graph", "Board", "Cluster")) { "图或分组须放在独立的 diagram 或 Board 中：$childId" }
            child["children"]?.jsonArray?.forEach { checkCompanion(it.jsonPrimitive.content) }
            child["slots"]?.jsonObject?.values?.forEach { children ->
                children.jsonArray.forEach { checkCompanion(it.jsonPrimitive.content) }
            }
        }
        companions.forEach { checkCompanion(it.jsonPrimitive.content) }
    }

    private fun type(elements: JsonObject, id: String): String =
        elements.getValue(id).jsonObject.getValue("type").jsonPrimitive.content

    private fun validPointer(path: String): Boolean =
        path.startsWith('/') && !Regex("~(?![01])").containsMatchIn(path)
}
