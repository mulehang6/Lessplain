package com.lessplain.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lessplain.lesson.LessonBlock
import com.lessplain.lesson.LessonExample
import com.lessplain.lesson.LessonTextRun
import com.lessplain.lesson.LessonSourceBinding
import com.lessplain.lesson.LessonSourceFocus
import com.lessplain.model.SourceAnchor

internal data class LessonSubmission(val title: String, val blocks: List<LessonBlock>, val examples: List<LessonExample>)

/** 教材协议不接受组件代码或坐标；图块复用既有图解析器的安全边界。 */
internal fun parseLessonPayload(json: String): LessonSubmission {
    val root = JsonParser.parseString(json).objectValue("lesson")
    return LessonSubmission(root.string("title") ?: "", root.array("blocks").map { entry ->
        val block = entry.objectValue("block")
        LessonBlock(
            id = requireNotNull(block.string("id")) { "教材块缺少 id" },
            kind = requireNotNull(block.string("kind")) { "教材块缺少 kind" },
            text = block.string("text") ?: "",
            level = block.get("level")?.asInt ?: 0,
            language = block.string("language") ?: "kotlin",
            exampleId = block.string("exampleId"),
            columns = block.array("columns").map { it.asString },
            rows = block.array("rows").map { row ->
                require(row.isJsonArray) { "表格行必须是数组" }
                row.asJsonArray.map { it.asString }
            },
            graph = block.get("graph")?.takeUnless(JsonElement::isJsonNull)?.let { parseGraphPayload(it.toString()) },
            source = block.get("source")?.takeUnless(JsonElement::isJsonNull)?.objectValue("source")?.let { source ->
                SourceAnchor(
                    path = requireNotNull(source.string("path")) { "源码引用缺少 path" },
                    symbol = source.string("symbol"),
                    text = requireNotNull(source.string("text")) { "源码引用缺少 text" },
                    version = source.string("version") ?: "",
                )
            },
            richText = block.get("richText")?.takeUnless(JsonElement::isJsonNull)?.let {
                block.array("richText").map { item -> item.objectValue("richText").let { run ->
                    LessonTextRun(requireNotNull(run.string("text")), run.string("style") ?: "plain", run.string("href"))
                } }
            },
            sourceBindings = block.array("sourceBindings").map { item ->
                val binding = item.objectValue("sourceBinding")
                LessonSourceBinding(requireNotNull(binding.string("id")), requireNotNull(binding.string("label")),
                    parseLessonFocus(requireNotNull(binding.get("focus"))), binding.get("start")?.asInt, binding.get("end")?.asInt)
            }.takeIf { it.isNotEmpty() },
        )
    }, root.array("examples").map { entry ->
        val example = entry.objectValue("example")
        LessonExample(
            requireNotNull(example.string("id")) { "示例缺少 id" },
            requireNotNull(example.string("fileName")) { "示例缺少 fileName" },
            requireNotNull(example.string("code")) { "示例缺少 code" },
            example.array("dependencies").map { it.asString },
        )
    })
}

private fun parseLessonFocus(json: JsonElement): LessonSourceFocus {
    val focus = json.objectValue("focus")
    val anchor = requireNotNull(focus.get("anchor")).objectValue("anchor")
    return LessonSourceFocus(SourceAnchor(requireNotNull(anchor.string("path")), anchor.string("symbol"),
        requireNotNull(anchor.string("text")), anchor.string("version") ?: ""),
        requireNotNull(focus.get("start")).asInt, requireNotNull(focus.get("end")).asInt)
}

private fun JsonElement.objectValue(name: String): JsonObject {
    require(isJsonObject) { "$name 必须是 JSON 对象" }
    return asJsonObject
}

private fun JsonObject.string(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$name 必须是字符串" }
    it.asString
}

private fun JsonObject.array(name: String): List<JsonElement> = get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    require(it.isJsonArray) { "$name 必须是数组" }
    it.asJsonArray.toList()
}.orEmpty()
