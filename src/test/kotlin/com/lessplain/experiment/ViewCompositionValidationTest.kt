package com.lessplain.experiment

import com.lessplain.experiment.core.ViewCompositionValidation
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ViewCompositionValidationTest {
    private fun validate(elements: String) = ViewCompositionValidation.validate(Json.parseToJsonElement(elements).jsonObject)

    @Test fun mixedClusterKeepsGraphAndCompanionSeparate() {
        validate("""{
          "board":{"type":"Board","props":{},"children":["cluster"]},
          "cluster":{"type":"Cluster","props":{"title":"标签缓存"},"slots":{"diagram":["graph"],"companion":["json"]}},
          "graph":{"type":"Graph","props":{"selectionPath":"/graphs/cache/selectedNode"}},
          "json":{"type":"JsonView","props":{"value":{}}}
        }""")
        validate("""{"cluster":{"type":"Cluster","props":{"title":"字段"},"slots":{"companion":["text"]}},"text":{"type":"Text","props":{"text":"记录 ID"}}}""")
    }

    @Test fun diagramSlotRejectsTextAndMultipleGraphs() {
        assertFailsWith<IllegalArgumentException> {
            validate("""{"c":{"type":"Cluster","props":{},"slots":{"diagram":["text"]}},"text":{"type":"Text","props":{}}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            validate("""{"c":{"type":"Cluster","props":{},"slots":{"diagram":["a","b"]}},"a":{"type":"Graph","props":{}},"b":{"type":"Graph","props":{}}}""")
        }
    }

    @Test fun wrongMembershipCannotCreateNestedCanvasOrUncontrolledGraph() {
        assertFailsWith<IllegalArgumentException> {
            validate("""{"b":{"type":"Board","props":{},"children":["g"]},"g":{"type":"Graph","props":{}}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            validate("""{"c":{"type":"Cluster","props":{},"slots":{"companion":["stack"]}},"stack":{"type":"Stack","props":{},"children":["g"]},"g":{"type":"Graph","props":{}}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            validate("""{"c":{"type":"Cluster","props":{},"slots":{"other":[]}}}""")
        }
    }

    @Test fun eachGraphHasAnIndependentLiteralPointer() {
        validate("""{"a":{"type":"Graph","props":{"selectionPath":"/a~1b/~0node"}},"b":{"type":"Graph","props":{"selectionPath":"/other"}}}""")
        for (bad in listOf("selected", "/bad~node")) {
            assertFailsWith<IllegalArgumentException> { validate("""{"g":{"type":"Graph","props":{"selectionPath":"$bad"}}}""") }
        }
        assertFailsWith<IllegalArgumentException> {
            validate("""{"a":{"type":"Graph","props":{"selectionPath":"/selection"}},"b":{"type":"Graph","props":{"selectionPath":"/selection"}}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            validate("""{"a":{"type":"Graph","props":{}},"b":{"type":"Graph","props":{"selectionPath":"/graphs/a/selectedNode"}}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            validate("""{"a":{"type":"Graph","props":{"selectionPath":"/selection"}},"b":{"type":"Graph","props":{"selectionPath":"/selection/node"}}}""")
        }
    }
}
