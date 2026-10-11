package com.lessplain.model

/** 固定源码片段；行号仅是提示，不是其身份。 */
data class SourceAnchor(
    val path: String,
    val symbol: String?,
    val text: String,
    val version: String,
    val lineHint: Int? = null,
    val fingerprint: String? = null,
)

data class GraphNode(
    val id: String,
    val label: String,
    val kind: String = "step",
    val anchor: SourceAnchor? = null,
    val evidenceIds: List<String> = emptyList(),
    /** 业务边界，不依赖节点类别和图的入出度。 */
    val flowRoles: List<String> = emptyList(),
    /** 当前解释范围的 success 或 failure；null 表示结果未归类。 */
    val endOutcome: String? = null,
)

data class GraphEdge(
    val id: String,
    val from: String,
    val to: String,
    /** 关系类型为 control_flow、call、data_flow、dependency 或 structure。 */
    val relation: String,
    val condition: String? = null,
    val coverage: String? = null,
    val evidenceIds: List<String> = emptyList(),
    /** true 表示关系由证据推断；缺省保留旧图的呈现方式。 */
    val inferred: Boolean? = null,
)

data class Evidence(
    val id: String,
    val anchor: SourceAnchor,
    val note: String? = null,
    val dependencies: List<SourceAnchor> = emptyList(),
)

data class UnderstandingGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val evidence: List<Evidence> = emptyList(),
    val clusters: List<GraphCluster> = emptyList(),
)

data class UnderstandingContext(
    val token: String,
    val projectId: String,
    val recordId: String?,
    val recordRevision: Int?,
    val nodeId: String?,
    val anchor: SourceAnchor?,
    val staticPath: List<String>,
    val createdAt: Long,
)

data class UnderstandingQuestion(
    val id: String,
    val text: String,
    val contextToken: String,
    val ownerRecordId: String?,
    val status: String,
    val createdAt: Long,
)

data class UnderstandingRecord(
    val id: String,
    val revision: Int,
    val parentId: String?,
    val questionId: String?,
    val question: String,
    val answer: String,
    val contextToken: String?,
    val graph: UnderstandingGraph,
    val createdAt: Long,
    val displayTitle: String? = null,
)

data class UnderstandingAnswer(
    val id: String,
    val questionId: String,
    val answer: String,
    val evidence: List<Evidence>,
    val createdAt: Long,
    val supersedesId: String? = null,
)

data class BrowseState(
    val recordId: String,
    val nodeId: String? = null,
    val staticPath: List<String> = emptyList(),
    val zoom: Double = 1.0,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
    val hasPan: Boolean = false,
    val navigationState: RouteNavigationState? = null,
)

/** 阅读路线中尚未确定的上游前缀和明确选定的后缀。 */
data class RouteNavigationState(
    val prefixNodeId: String,
    val selectedEdgeIds: List<String> = emptyList(),
)
