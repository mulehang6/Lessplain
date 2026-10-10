package com.master.lessplain.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.master.lessplain.model.Evidence
import com.master.lessplain.model.GraphEdge
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.GraphCluster
import com.master.lessplain.model.ClusterUpdate
import com.master.lessplain.model.UNDERSTANDING_SCHEMA_REVISION
import com.master.lessplain.model.SourceAnchor
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingInputException

internal data class GraphSubmission(val graph: UnderstandingGraph, val clusterUpdate: ClusterUpdate)

internal fun parseGraphPayload(json: String, schemaVersion: Int = UNDERSTANDING_SCHEMA_REVISION): UnderstandingGraph =
    parseGraphSubmission(json, schemaVersion).graph

internal fun parseGraphSubmission(json: String, schemaVersion: Int): GraphSubmission {
    val root = parseObject(json, "graph")
    if (schemaVersion == 3 && root.has("clusters")) throw UnderstandingInputException("版本 3 不支持语义分组 clusters")
    val update = root.get("clusters")?.takeUnless(JsonElement::isJsonNull)?.let { value ->
        if (!value.isJsonArray) throw UnderstandingInputException("clusters 必须是数组")
        ClusterUpdate.Replace(value.asJsonArray.map { entry ->
            val item = entry.asObject("cluster")
            item.rejectCoordinates()
            if (item.has("clusters") || item.has("children")) throw UnderstandingInputException("只支持单层分组")
            GraphCluster(item.requiredString("id"), item.requiredString("title"), item.optionalStrings("nodeIds"))
        })
    } ?: ClusterUpdate.Keep
    val graph = UnderstandingGraph(
        nodes = root.requiredArray("nodes").map { entry ->
            val item = entry.asObject("node")
            item.rejectCoordinates()
            GraphNode(
                id = item.requiredString("id"),
                label = item.requiredString("label"),
                kind = item.optionalString("kind") ?: "step",
                anchor = item.optionalObject("anchor")?.toAnchor(),
                evidenceIds = item.optionalStrings("evidenceIds"),
                flowRoles = item.optionalStrings("flowRoles").distinct().also { roles ->
                    if (roles.any { it != "start" && it != "end" }) {
                        throw UnderstandingInputException("flowRoles 只允许 start 和 end")
                    }
                },
                endOutcome = item.optionalStrictString("endOutcome"),
            )
        },
        edges = root.requiredArray("edges").map { entry ->
            val item = entry.asObject("edge")
            item.rejectCoordinates()
            GraphEdge(
                id = item.requiredString("id"),
                from = item.requiredString("from"),
                to = item.requiredString("to"),
                relation = item.requiredString("relation"),
                condition = item.optionalString("condition"),
                coverage = item.optionalString("coverage"),
                evidenceIds = item.optionalStrings("evidenceIds"),
            )
        },
        evidence = root.optionalArray("evidence").map { it.asObject("evidence").toEvidence() },
        clusters = (update as? ClusterUpdate.Replace)?.clusters.orEmpty(),
    )
    return GraphSubmission(graph, update)
}

internal fun parseEvidencePayload(json: String): List<Evidence> = try {
    val parsed = JsonParser.parseString(json)
    if (!parsed.isJsonArray) throw UnderstandingInputException("evidence 必须是 JSON 数组")
    parsed.asJsonArray.map { it.asObject("evidence").toEvidence() }
} catch (failure: IllegalStateException) {
    throw UnderstandingInputException("evidence JSON 无效：${failure.message}")
} catch (failure: JsonParseException) {
    throw UnderstandingInputException("evidence JSON 无效：${failure.message}")
}

private fun JsonObject.toEvidence(): Evidence = Evidence(
    id = requiredString("id"),
    anchor = requiredObject("anchor").toAnchor(),
    note = optionalString("note"),
    dependencies = optionalArray("dependencies").map { it.asObject("dependency").toAnchor() },
)

private fun JsonObject.toAnchor(): SourceAnchor = SourceAnchor(
    path = requiredString("path"),
    symbol = optionalString("symbol"),
    text = requiredString("text"),
    version = requiredString("version"),
    lineHint = get("lineHint")?.takeUnless(JsonElement::isJsonNull)?.asInt,
    fingerprint = optionalString("fingerprint"),
)

private fun parseObject(json: String, name: String): JsonObject = try {
    JsonParser.parseString(json).asObject(name)
} catch (failure: IllegalStateException) {
    throw UnderstandingInputException("$name JSON 无效：${failure.message}")
} catch (failure: JsonParseException) {
    throw UnderstandingInputException("$name JSON 无效：${failure.message}")
}

private fun JsonElement.asObject(name: String): JsonObject {
    if (!isJsonObject) throw UnderstandingInputException("$name 必须是 JSON 对象")
    return asJsonObject
}

private fun JsonObject.requiredString(name: String): String = optionalString(name)
    ?: throw UnderstandingInputException("缺少 $name")

private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asString

private fun JsonObject.optionalStrictString(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw UnderstandingInputException("$name 必须是字符串")
    it.asString
}

private fun JsonObject.requiredObject(name: String): JsonObject = get(name)?.asObject(name)
    ?: throw UnderstandingInputException("缺少 $name")

private fun JsonObject.optionalObject(name: String): JsonObject? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asObject(name)

private fun JsonObject.requiredArray(name: String): JsonArray = get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
    ?: throw UnderstandingInputException("缺少 $name 数组")

private fun JsonObject.optionalArray(name: String): JsonArray = get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    if (!it.isJsonArray) throw UnderstandingInputException("$name 必须是数组")
    it.asJsonArray
} ?: JsonArray()

private fun JsonObject.optionalStrings(name: String): List<String> = optionalArray(name).map {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw UnderstandingInputException("$name 必须是字符串数组")
    it.asString
}

private fun JsonObject.rejectCoordinates() {
    if (keySet().any { it in setOf("x", "y", "position", "coordinates") }) {
        throw UnderstandingInputException("图节点和关系不能提交坐标；布局由 IDE 计算")
    }
}
