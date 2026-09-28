package com.master.understanding.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.master.understanding.model.Evidence
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.SourceAnchor
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingInputException

internal fun parseGraphPayload(json: String): UnderstandingGraph {
    val root = parseObject(json, "graph")
    return UnderstandingGraph(
        nodes = root.requiredArray("nodes").map { entry ->
            val item = entry.asObject("node")
            item.rejectCoordinates()
            GraphNode(
                id = item.requiredString("id"),
                label = item.requiredString("label"),
                kind = item.optionalString("kind") ?: "step",
                anchor = item.optionalObject("anchor")?.toAnchor(),
                evidenceIds = item.optionalStrings("evidenceIds"),
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
    )
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
