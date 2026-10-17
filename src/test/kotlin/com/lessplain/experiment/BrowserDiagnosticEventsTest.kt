package com.lessplain.experiment

import com.lessplain.experiment.browser.BrowserDiagnosticEvents
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class BrowserDiagnosticEventsTest {
    @Test fun keepsBoundedOrderedEventsAndIndependentSnapshots() {
        val events = BrowserDiagnosticEvents(3)
        events.record("created")
        val before = events.snapshot()
        repeat(5) { events.record("probe", mapOf("index" to JsonPrimitive(it))) }
        assertEquals(listOf(2, 3, 4), events.snapshot().map { it.jsonObject.getValue("index").jsonPrimitive.int })
        assertEquals("created", before.single().jsonObject.getValue("kind").jsonPrimitive.content)
        assertTrue(events.snapshot().all { it.jsonObject.getValue("elapsedMs").jsonPrimitive.long >= 0 })
    }
}
