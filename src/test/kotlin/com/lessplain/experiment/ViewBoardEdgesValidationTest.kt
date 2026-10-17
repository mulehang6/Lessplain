package com.lessplain.experiment

import com.lessplain.experiment.core.ViewBoardEdgesValidation
import com.lessplain.experiment.model.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ViewBoardEdgesValidationTest {
    private fun document(edge: String) = ViewDocument(
        "跨组调用", JsonPayload(Json.parseToJsonElement("""{
          "root":"board","elements":{
            "board":{"type":"Board","props":{"edges":[$edge]},"children":["left","right"]},
            "left":{"type":"Cluster","props":{"title":"入口"},"slots":{"diagram":["ga"]}},
            "right":{"type":"Cluster","props":{"title":"缓存"},"slots":{"diagram":["gb"]}},
            "ga":{"type":"Graph","props":{"nodes":[{"id":"same","label":"入口"}],"edges":[]}},
            "gb":{"type":"Graph","props":{"nodes":[{"id":"same","label":"缓存"}],"edges":[]}},
            "outside":{"type":"Graph","props":{"nodes":[{"id":"same","label":"外部"}],"edges":[]}}
          }}""").jsonObject), sources = mapOf("proof" to SourceReference("Cache.java", "cache.get(key)")),
    )
    private val edge = """{"id":"call","source":{"elementId":"ga","nodeId":"same"},"target":{"elementId":"gb","nodeId":"same"},"kind":"call","evidence":["proof"]}"""
    private fun validate(document: ViewDocument) {
        val elements = document.spec.value.getValue("elements").jsonObject
        ViewBoardEdgesValidation.validate(elements.getValue("board").jsonObject, elements, document)
    }

    @Test fun sameLocalNodeIdsStillIdentifyDifferentGroups() { validate(document(edge)) }

    @Test fun endpointsMustExistAndBelongToTheOwningBoard() {
        for (bad in listOf(
            edge.replace("\"elementId\":\"gb\"", "\"elementId\":\"outside\""),
            edge.replace("\"nodeId\":\"same\"", "\"nodeId\":\"missing\""),
            edge.replace("\"elementId\":\"gb\"", "\"elementId\":\"ga\""),
        )) assertFailsWith<IllegalArgumentException> { validate(document(bad)) }
    }

    @Test fun evidenceAndIdentityChecksMatchLocalEdges() {
        assertFailsWith<IllegalArgumentException> { validate(document("$edge,$edge")) }
        assertFailsWith<IllegalArgumentException> { validate(document(edge.replace("proof", "missing"))) }
        assertFailsWith<IllegalArgumentException> {
            validate(document(edge.replace("\"evidence\":[\"proof\"]", "\"inferred\":true")))
        }
    }
}
