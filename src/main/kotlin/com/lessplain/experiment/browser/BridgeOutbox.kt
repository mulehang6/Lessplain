package com.lessplain.experiment.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 页面 ready 前只保留最新快照；旧页面的结果不可投递到新页面。 */
internal class BridgeOutbox(private val send: (String) -> Unit) {
    private var generation = 0L
    private var ready = false
    private var snapshot: String? = null
    private val replies = ArrayDeque<String>()
    private val events = ArrayDeque<String>()
    @Synchronized fun newPage(): Long {
        generation++
        ready = false
        snapshot = null
        replies.clear()
        events.clear()
        return generation
    }
    @Synchronized fun ready(page: Long) {
        if (page != generation) return
        ready = true
        while (replies.isNotEmpty()) send(replies.removeFirst())
        snapshot?.let(send); snapshot = null
        while (events.isNotEmpty()) send(events.removeFirst())
    }
    @Synchronized fun snapshot(value: String) {
        if (ready) send(value) else {
            val previous = snapshot
            val incoming = Json.parseToJsonElement(value).jsonObject
            val merged = previous?.let { Json.parseToJsonElement(it).jsonObject.toMutableMap() } ?: mutableMapOf()
            if ("select" in incoming) merged.remove("showHistory")
            if ("showHistory" in incoming) merged.remove("select")
            merged.putAll(incoming)
            snapshot = JsonObject(merged).toString()
        }
    }
    @Synchronized fun reply(page: Long, value: String) {
        if (page != generation) return
        if (ready) send(value) else {
            check(replies.size < 64) { "页面待发送响应过多" }
            replies += value
        }
    }
    /** 不合并有顺序语义的导航请求；页面尚未 ready 时按原顺序排队。 */
    @Synchronized fun event(page: Long, value: String) {
        if (page != generation) return
        if (ready) send(value) else {
            check(events.size < 64) { "页面待发送事件过多" }
            events += value
        }
    }
    @Synchronized fun close() { newPage() }
}
