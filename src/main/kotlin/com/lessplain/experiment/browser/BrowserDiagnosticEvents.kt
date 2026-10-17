package com.lessplain.experiment.browser

import kotlinx.serialization.json.*

/** 固定容量的本地时序记录，只接受调用端选择的状态和计数。 */
internal class BrowserDiagnosticEvents(private val capacity: Int = 500) {
    private val started = System.nanoTime()
    private val events = ArrayDeque<JsonObject>()

    @Synchronized fun record(kind: String, fields: Map<String, JsonPrimitive> = emptyMap()): JsonObject {
        val event = buildJsonObject {
            put("kind", kind)
            put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            fields.forEach { (key, value) -> put(key, value) }
        }
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
        return event
    }

    @Synchronized fun snapshot(): JsonArray = JsonArray(events.toList())
}
