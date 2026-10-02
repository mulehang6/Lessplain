package com.master.understanding.model

const val UNDERSTANDING_SCHEMA_VERSION = 3

/** A fixed code excerpt. The line is a hint, never its identity. */
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
    /** Business boundaries, independent of node category and graph degree. */
    val flowRoles: List<String> = emptyList(),
    /** success/failure at this explanation boundary; null leaves the outcome unclassified. */
    val endOutcome: String? = null,
)

data class GraphEdge(
    val id: String,
    val from: String,
    val to: String,
    /** control_flow, call, data_flow, dependency, or structure. */
    val relation: String,
    val condition: String? = null,
    val coverage: String? = null,
    val evidenceIds: List<String> = emptyList(),
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
)
