package com.lessplain.experiment

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.mcpserver.McpToolSchema
import com.lessplain.experiment.mcp.ViewToolset
import com.lessplain.experiment.model.ViewDocument
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.reflect.KParameter
import kotlin.test.*

/** 仅测试调用 SDK 的实际 schema 生成器，生产插件不依赖实现包。 */
class McpSchemaTest {
    private val utility = Class.forName("com.intellij.mcpserver.impl.util.Schema_utilKt")
    @Test fun structuredViewAcceptsArbitrarySpec() {
        val schema = utility.methods.single { it.name == "parametersSchema" }
            .invoke(null, ViewToolset::present, emptyArray<KParameter>()) as McpToolSchema
        val serialized = schema.prettyPrint()
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val input = """{"context":"context:fixed","view":{"question":"对象是什么？","spec":{"root":"g","elements":{"g":{"type":"Graph","props":{"nodes":[{"id":"a","label":"a"}],"edges":[]}}},"state":{"hit":true}}}}"""
        val failures = factory.getSchema(serialized).validate(ObjectMapper().readTree(input))
        assertTrue(failures.isEmpty(), failures.joinToString())
        val decoded = Json.decodeFromString<ViewDocument>(Json.parseToJsonElement(input).jsonObject.getValue("view").toString())
        assertEquals("g", decoded.spec.value["root"]?.toString()?.trim('"'))
        assertFalse(serialized.contains("JsonElement"))
    }
    @Test fun allToolOutputsHaveRegisterableObjectSchemas() {
        val method = utility.methods.single { it.name == "returnTypeSchema" }
        listOf(ViewToolset::context, ViewToolset::present, ViewToolset::finishFeedback).forEach {
            val result = method.invoke(null, it) as McpToolSchema
            assertTrue(result.propertiesSchema.isNotEmpty())
        }
    }
}
