package com.lessplain.diagram

import com.google.gson.JsonParser
import com.lessplain.mcp.parseGraphPayload
import com.lessplain.model.UnderstandingGraph

/** 仅用于几何的测试数据，内部追问组不作为公开协议字段解析。 */
internal fun groupedFlowFixture(name: String): Pair<UnderstandingGraph, List<DiagramCluster>> {
    val root = JsonParser.parseString(ElkClusterLayoutTest::class.java.getResource("/diagram/$name.json")!!.readText()).asJsonObject
    val groups = root.remove("clusters").asJsonArray.map { value ->
        val group = value.asJsonObject
        DiagramCluster(group["id"].asString, group["title"].asString,
            group["nodeIds"].asJsonArray.mapTo(linkedSetOf()) { it.asString })
    }
    return parseGraphPayload(root.toString()) to groups
}
