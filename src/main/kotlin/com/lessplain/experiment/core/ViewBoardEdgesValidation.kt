package com.lessplain.experiment.core

import com.lessplain.experiment.model.ViewDocument
import kotlinx.serialization.json.*

/** 跨组边属于其 Board，用显式元素和节点身份区分各图内重复的局部 ID。 */
internal object ViewBoardEdgesValidation {
    fun validate(board: JsonObject, elements: JsonObject, document: ViewDocument) {
        val edges = board.getValue("props").jsonObject["edges"]?.jsonArray.orEmpty()
        val graphIds = board["children"]?.jsonArray.orEmpty().flatMap { clusterId ->
            elements.getValue(clusterId.jsonPrimitive.content).jsonObject["slots"]?.jsonObject
                ?.get("diagram")?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        }.toSet()
        val edgeIds = mutableSetOf<String>()
        edges.forEach { raw ->
            val edge = raw.jsonObject
            require(edgeIds.add(edge.getValue("id").jsonPrimitive.content)) { "跨组关系 ID 重复" }
            val source = requireEndpoint(edge.getValue("source"), graphIds, elements)
            val target = requireEndpoint(edge.getValue("target"), graphIds, elements)
            require(source != target) { "同组关系须放在 Graph.edges 中" }
            val evidence = edge["evidence"]?.jsonArray.orEmpty()
            evidence.forEach { id ->
                require(id.jsonPrimitive.content in document.sources.orEmpty()) { "跨组关系源码引用不存在：$id" }
            }
            require(edge["inferred"]?.jsonPrimitive?.booleanOrNull != true || evidence.isNotEmpty()) {
                "推断跨组关系须有源码依据"
            }
        }
    }

    private fun requireEndpoint(raw: JsonElement, graphIds: Set<String>, elements: JsonObject): String {
        val endpoint = raw.jsonObject
        val elementId = endpoint.getValue("elementId").jsonPrimitive.content
        val nodeId = endpoint.getValue("nodeId").jsonPrimitive.content
        require(elementId in graphIds) { "跨组关系引用了不属于此 Board 的图：$elementId" }
        val graph = elements.getValue(elementId).jsonObject
        require(graph.getValue("type").jsonPrimitive.content == "Graph") { "跨组端点必须引用 Graph" }
        val nodes = graph.getValue("props").jsonObject.getValue("nodes").jsonArray
        require(nodes.any { it.jsonObject.getValue("id").jsonPrimitive.content == nodeId }) {
            "跨组关系端点不存在：$elementId/$nodeId"
        }
        return elementId
    }
}
