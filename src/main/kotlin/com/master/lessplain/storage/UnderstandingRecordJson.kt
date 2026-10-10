package com.master.lessplain.storage

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.master.lessplain.model.UnderstandingRecord

/** Normalize legacy graph JSON in memory only; reading a record never rewrites its history. */
internal fun Gson.readUnderstandingRecord(json: String): UnderstandingRecord {
    val root = JsonParser.parseString(json).asJsonObject
    root.getAsJsonObject("graph")?.let { graph ->
        if (!graph.has("clusters") || graph.get("clusters").isJsonNull) graph.add("clusters", JsonArray())
    }
    return fromJson(root, UnderstandingRecord::class.java)
}
