package com.master.lessplain.diagram

import com.google.gson.JsonParser
import com.master.lessplain.mcp.parseGraphPayload
import com.master.lessplain.model.UnderstandingGraph

/** Geometry-only fixtures; internal question groups are never parsed as public protocol fields. */
internal fun groupedFlowFixture(name: String): Pair<UnderstandingGraph, List<DiagramCluster>> {
    val root = JsonParser.parseString(ElkClusterLayoutTest::class.java.getResource("/diagram/$name.json")!!.readText()).asJsonObject
    val groups = root.remove("clusters").asJsonArray.map { value ->
        val group = value.asJsonObject
        DiagramCluster(group["id"].asString, group["title"].asString,
            group["nodeIds"].asJsonArray.mapTo(linkedSetOf()) { it.asString })
    }
    return parseGraphPayload(root.toString()) to groups
}
