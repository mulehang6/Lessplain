package com.lessplain.experiment.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.lessplain.experiment.model.JsonPayload
import com.lessplain.experiment.model.ViewDocument
import com.lessplain.experiment.model.CodeSampleTarget
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.serialization.json.*

/** 只加载插件内 catalog；调用者提交的页面不能替换 schema 或注册组件。 */
class ViewValidation(catalogText: String) {
    val catalog = JsonPayload(Json.parseToJsonElement(catalogText).jsonObject)
    private val mapper = ObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(catalog.value.getValue("schema").toString())

    fun validate(document: ViewDocument) {
        validateCodeSamples(document)
        require(document.question.length <= 10000 && (document.title?.length ?: 0) <= 200) { "问题或标题过长" }
        require(document.spec.value.toString().length <= 1_000_000) { "页面内容过大" }
        val failures = schema.validate(mapper.readTree(document.spec.value.toString()))
        require(failures.isEmpty()) { failures.take(12).joinToString("\n") { it.message } }
        require(document.sources.orEmpty().size <= 200 && document.demos.orEmpty().size <= 20) { "源码或示例数量过多" }
        document.sources.orEmpty().forEach { (id, source) ->
            require(id.isNotBlank() && source.text.isNotBlank() && source.text.length <= 100000) { "源码引用不能为空或过大：" + id }
        }
        require(document.demos.orEmpty().values.sumOf { it.html.length } <= 1_000_000) { "示例资源过大" }
        val spec = document.spec.value
        val elements = spec.getValue("elements").jsonObject
        require(elements.size in 1..500) { "页面组件数量超限" }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String, depth: Int) {
            require(depth <= 40) { "页面嵌套过深" }
            require(id !in visiting) { "页面组件存在循环：" + id }
            require(id !in visited) { "同一组件不能有多个父组件：" + id }
            val element = elements[id]?.jsonObject ?: error("组件不存在：" + id)
            visiting += id
            element["children"]?.jsonArray?.forEach { visit(it.jsonPrimitive.content, depth + 1) }
            element["slots"]?.jsonObject?.values?.forEach { children ->
                children.jsonArray.forEach { visit(it.jsonPrimitive.content, depth + 1) }
            }
            visiting -= id
            visited += id
        }
        visit(spec.getValue("root").jsonPrimitive.content, 0)
        require(visited.size == elements.size) { "包含根组件不可达的内容" }
        ViewCompositionValidation.validate(elements, spec.getValue("root").jsonPrimitive.content)
        elements.forEach { (id, value) ->
            val element = value.jsonObject
            val props = element.getValue("props").jsonObject
            when (element.getValue("type").jsonPrimitive.content) {
                "SourceLink", "CodeExcerpt" -> requireSource(props.getValue("source"), document)
                "CodeSample" -> requireCodeSample(document, CodeSampleTarget(props.getValue("sample").jsonPrimitive.content,
                    props["startLine"]?.jsonPrimitive?.int ?: 1, props["endLine"]?.jsonPrimitive?.int))
                "Demo" -> require(props.getValue("demo").jsonPrimitive.content in document.demos.orEmpty()) { "示例不存在：" + id }
                "Graph" -> validateGraph(props, document)
                "Board" -> ViewBoardEdgesValidation.validate(element, elements, document)
            }
        }
    }

    private fun requireSource(id: JsonElement, document: ViewDocument) {
        require(id is JsonPrimitive && id.content in document.sources.orEmpty()) { "源码引用不存在：" + id }
    }

    private fun validateGraph(props: JsonObject, document: ViewDocument) {
        val nodes = props.getValue("nodes").jsonArray.map { it.jsonObject }
        val edges = props.getValue("edges").jsonArray.map { it.jsonObject }
        val ids = nodes.map { it.getValue("id").jsonPrimitive.content }
        require(ids.distinct().size == ids.size) { "图节点 ID 重复" }
        require(edges.map { it.getValue("id").jsonPrimitive.content }.distinct().size == edges.size) { "图关系 ID 重复" }
        nodes.forEach { node ->
            node["source"]?.let { requireSource(it, document) }
            node["sample"]?.let { requireCodeSample(document, Json.decodeFromJsonElement(it)) }
            require(node["source"] == null || node["sample"] == null) { "同一节点只能绑定项目源码或教学代码" }
        }
        edges.forEach { edge ->
            require(edge.getValue("source").jsonPrimitive.content in ids && edge.getValue("target").jsonPrimitive.content in ids) { "关系端点不存在" }
            edge["evidence"]?.jsonArray?.forEach { requireSource(it, document) }
            require(edge["inferred"]?.jsonPrimitive?.booleanOrNull != true || !edge["evidence"]?.jsonArray.isNullOrEmpty()) { "推断关系须有源码依据" }
        }
    }
}
