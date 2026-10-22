package com.lessplain.diagram

import com.google.gson.JsonParser
import com.google.gson.Gson
import com.lessplain.model.UnderstandingGraph

/** 仅用于几何的测试数据，内部追问组不作为公开协议字段解析。 */
internal fun groupedFlowFixture(name: String): Pair<UnderstandingGraph, List<DiagramCluster>> {
    val root = JsonParser.parseString(ElkClusterLayoutTest::class.java.getResource("/diagram/$name.json")!!.readText()).asJsonObject
    val groups = root.remove("clusters").asJsonArray.map { value ->
        val group = value.asJsonObject
        DiagramCluster(group["id"].asString, group["title"].asString,
            group["nodeIds"].asJsonArray.mapTo(linkedSetOf()) { it.asString })
    }
    root.getAsJsonArray("nodes").forEach { value -> value.asJsonObject.apply { if (!has("kind")) addProperty("kind", "step") } }
    val graph = Gson().fromJson(root, UnderstandingGraph::class.java)
    return graph.copy(nodes = graph.nodes.map { it.copy(evidenceIds = it.evidenceIds.orEmpty(), flowRoles = it.flowRoles.orEmpty()) },
        edges = graph.edges.map { it.copy(evidenceIds = it.evidenceIds.orEmpty()) }, evidence = graph.evidence.orEmpty(), clusters = emptyList()) to groups
}
