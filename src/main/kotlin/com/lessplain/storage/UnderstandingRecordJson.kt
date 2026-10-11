package com.lessplain.storage

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.lessplain.model.UnderstandingRecord

/** 仅在内存中规范旧图 JSON，读取记录不改写其历史。 */
internal fun Gson.readUnderstandingRecord(json: String): UnderstandingRecord {
    val root = JsonParser.parseString(json).asJsonObject
    root.getAsJsonObject("graph")?.let { graph ->
        if (!graph.has("clusters") || graph.get("clusters").isJsonNull) graph.add("clusters", JsonArray())
    }
    return fromJson(root, UnderstandingRecord::class.java)
}
