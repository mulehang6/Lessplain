package com.lessplain.experiment.browser

import kotlinx.serialization.json.*

/** 固定容量的本地时序记录，只接受调用端选择的状态和计数。 */
internal class BrowserDiagnosticEvents(private val capacity: Int = 500, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val started = clock()
    private val events = ArrayDeque<JsonObject>()

    @Synchronized fun record(kind: String, fields: Map<String, JsonPrimitive> = emptyMap()): JsonObject {
        val event = buildJsonObject {
            put("kind", kind)
            put("elapsedMs", clock() - started)
            fields.filterKeys { it != "kind" && it != "elapsedMs" }.forEach { (key, value) -> put(key, value) }
        }
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
        prune()
        return event
    }

    private fun prune() {
        val cutoff = clock() - started - 120_000
        while (events.firstOrNull()?.get("elapsedMs")?.jsonPrimitive?.long?.let { it < cutoff } == true) events.removeFirst()
    }
    @Synchronized fun snapshot(): JsonArray { prune(); return JsonArray(events.toList()) }
}

/** 调度间隙视为休眠恢复；隐藏页和正常释放不判作 renderer 卡死。 */
internal class HeartbeatWatchdog(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var lastTick = clock()
    private var lastBeat = lastTick
    private var visible = false
    private var reported = false
    @Synchronized fun receive(showing: Boolean) { visible = showing; lastBeat = clock(); reported = false }
    @Synchronized fun poll(hostShowing: Boolean): Boolean {
        val now = clock()
        val resumed = now - lastTick > 10_000
        lastTick = now
        if (resumed || !hostShowing || !visible) { lastBeat = now; return false }
        if (now - lastBeat < 8000 || reported) return false
        reported = true
        return true
    }
}
