package com.lessplain.experiment

import com.lessplain.experiment.core.ViewValidation
import com.lessplain.experiment.model.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class ViewValidationTest {
    private val validation = ViewValidation(requireNotNull(javaClass.getResourceAsStream("/webview/catalog.json")).bufferedReader().use { it.readText() })
    private fun doc(spec: String, sources: Map<String, SourceReference> = emptyMap()) = ViewDocument("是什么？", JsonPayload(Json.parseToJsonElement(spec).jsonObject), sources = sources)
    @Test fun disconnectedConceptsAndBoundState() {
        validation.validate(doc("""{"root":"graph","state":{"value":1},"elements":{"graph":{"type":"Graph","props":{"nodes":[{"id":"id","label":"记录 ID"},{"id":"name","label":"标签名称"}],"edges":[]}}}}"""))
        val stateKey = "$" + "state"
        validation.validate(doc("""{"root":"json","state":{"value":1},"elements":{"json":{"type":"JsonView","props":{"value":{"$stateKey":"/value"}}}}}"""))
    }
    @Test fun invalidPropsAndReferences() {
        assertFailsWith<IllegalArgumentException> { validation.validate(doc("""{"root":"graph","elements":{"graph":{"type":"Graph","props":{"nodes":[],"edges":[]}}}}""")) }
        assertFailsWith<IllegalArgumentException> { validation.validate(doc("""{"root":"source","elements":{"source":{"type":"SourceLink","props":{"source":"missing","label":"源代码"}}}}""")) }
        assertFailsWith<IllegalArgumentException> { validation.validate(doc("""{"root":"root","elements":{"root":{"type":"Stack","props":{},"children":["root"]}}}""")) }
    }
    @Test fun relationEvidenceAndEndpoints() {
        assertFailsWith<IllegalArgumentException> { validation.validate(doc("""{"root":"g","elements":{"g":{"type":"Graph","props":{"nodes":[{"id":"a","label":"a"}],"edges":[{"id":"e","source":"a","target":"missing","kind":"call"}]}}}}""")) }
        assertFailsWith<IllegalArgumentException> { validation.validate(doc("""{"root":"g","elements":{"g":{"type":"Graph","props":{"nodes":[{"id":"a","label":"a"}],"edges":[{"id":"e","source":"a","target":"a","kind":"call","inferred":true}]}}}}""")) }
    }

    @Test fun actualRedisRevisionUsesValidatedMixedClusters() {
        val document = Json.decodeFromString<ViewDocument>(Files.readString(Path.of("docs/examples/redis-cache-ranking.view.json")))
        validation.validate(document)
    }

    @Test fun tagConceptsUseExistingCatalogAndPreciseSources() {
        val document = Json.decodeFromString<ViewDocument>(Files.readString(Path.of("docs/examples/tag-concepts.view.json")))
        validation.validate(document)
        assertEquals(14, document.sources?.size)
        assertFalse(document.spec.value.toString().contains("CodeExcerpt"), "对照页按需导航，不复制整段源码")
    }

    @Test fun newGraphSubmissionCannotUseAnOrdinaryContainerRoot() {
        val failure = assertFailsWith<IllegalArgumentException> {
            validation.validate(doc("""{"root":"stack","elements":{
              "stack":{"type":"Stack","props":{"className":""},"children":["graph"]},
              "graph":{"type":"Graph","props":{"nodes":[{"id":"a","label":"缓存"}],"edges":[]}}
            }}"""))
        }
        assertContains(failure.message.orEmpty(), "含图页面须以")
        validation.validate(doc("""{"root":"stack","elements":{
          "stack":{"type":"Stack","props":{"className":""},"children":["text"]},
          "text":{"type":"Text","props":{"text":"字段说明"}}
        }}"""))
    }

    @Test fun graphCannotHideOtherComponentsInItsChildren() {
        val failure = assertFailsWith<IllegalArgumentException> {
            validation.validate(doc("""{"root":"graph","elements":{
              "graph":{"type":"Graph","props":{"nodes":[{"id":"a","label":"缓存"}],"edges":[]},"children":["text"]},
              "text":{"type":"Text","props":{"text":"不可见内容"}}
            }}"""))
        }
        assertContains(failure.message.orEmpty(), "graph")
    }

    @Test fun actualCatalogAcceptsQualifiedCrossClusterEndpoints() {
        val spec = """{"root":"board","elements":{
          "board":{"type":"Board","props":{"edges":[{"id":"call","source":{"elementId":"ga","nodeId":"a"},"target":{"elementId":"gb","nodeId":"b"},"kind":"call","evidence":["proof"]}]},"children":["ca","cb"]},
          "ca":{"type":"Cluster","props":{"title":"入口"},"slots":{"diagram":["ga"]}},
          "cb":{"type":"Cluster","props":{"title":"缓存"},"slots":{"diagram":["gb"]}},
          "ga":{"type":"Graph","props":{"nodes":[{"id":"a","label":"请求"}],"edges":[]}},
          "gb":{"type":"Graph","props":{"nodes":[{"id":"b","label":"缓存"}],"edges":[]}}
        }}"""
        validation.validate(doc(spec, mapOf("proof" to SourceReference("Cache.java", "cache.get(key)"))))
        assertFailsWith<IllegalArgumentException> {
            validation.validate(doc(spec.replace("\"nodeId\":\"b\"", "\"nodeId\":\"absent\""),
                mapOf("proof" to SourceReference("Cache.java", "cache.get(key)"))))
        }
    }

    @Test fun clusterSlotsAndGraphMembershipAreValidatedTogether() {
        assertFailsWith<IllegalArgumentException> {
            validation.validate(doc("""{"root":"c","elements":{"c":{"type":"Cluster","props":{"title":"对象"},"slots":{"diagram":["text"]}},"text":{"type":"Text","props":{"text":"字段"}}}}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            validation.validate(doc("""{"root":"c","elements":{"c":{"type":"Cluster","props":{"title":"对象"},"slots":{"unknown":["text"]}},"text":{"type":"Text","props":{"text":"字段"}}}}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            validation.validate(doc("""{"root":"b","elements":{"b":{"type":"Board","props":{},"children":["a","c"]},"a":{"type":"Cluster","props":{"title":"甲"},"slots":{"diagram":["ga"]}},"c":{"type":"Cluster","props":{"title":"乙"},"slots":{"diagram":["gb"]}},"ga":{"type":"Graph","props":{"nodes":[{"id":"na","label":"甲"}],"edges":[{"id":"e","source":"na","target":"nb","kind":"call"}]}},"gb":{"type":"Graph","props":{"nodes":[{"id":"nb","label":"乙"}],"edges":[]}}}}"""))
        }
    }
}
