package com.master.lessplain.diagram

import com.master.lessplain.model.UnderstandingGraph

/** 已有样例中经源码核实的业务边界，可视分叉由图后继决定。 */
internal fun semanticSampleFixture(name: String): UnderstandingGraph {
    val (base, _) = groupedFlowFixture(name)
    val success = if (name == "article-publish-clusters")
        setOf("response", "es_delete", "es_write", "stats_cache", "rank_listener", "sitemap_add")
        else setOf("response", "websocket")
    val unclassified = if (name == "article-publish-clusters") setOf("es_disabled", "es_skip")
        else setOf("wx_ack", "confirm_save", "author_callback", "read_result")
    val starts = if (name == "article-publish-clusters")
        setOf("post", "es_listener", "stats_listener", "rank_listener", "sitemap_listener")
        else setOf("request", "wx_callback", "user_confirm", "author_callback", "listener", "read_gate")
    return base.copy(nodes = base.nodes.map { node -> node.copy(
        flowRoles = buildList {
            if (node.id in starts) add("start")
            if (node.id in success || node.id in unclassified) add("end")
        },
        endOutcome = if (node.id in success) "success" else null,
    ) })
}
