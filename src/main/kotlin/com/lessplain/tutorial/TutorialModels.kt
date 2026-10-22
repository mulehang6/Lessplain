package com.lessplain.tutorial

import kotlinx.serialization.Serializable

/** 工具 DTO 与磁盘模型共用有限结构，避免 JSON 字符串及递归 schema。 */
@Serializable
data class TutorialNode(
    val id: String,
    val label: String,
    val sourceId: String? = null,
    val kind: String = "step",
    val evidenceIds: List<String> = emptyList(),
    val flowRoles: List<String> = emptyList(),
    val endOutcome: String? = null,
)

@Serializable
data class TutorialEdge(
    val id: String,
    val from: String,
    val to: String,
    val label: String = "",
    val relation: String = "control_flow",
    val condition: String? = null,
    val coverage: String? = null,
    val evidenceIds: List<String> = emptyList(),
    val inferred: Boolean? = null,
)

@Serializable
data class TutorialEvidence(val id: String, val sourceId: String, val note: String? = null, val dependencySourceIds: List<String> = emptyList())

@Serializable
data class TutorialCluster(val id: String, val label: String, val nodeIds: List<String>)

@Serializable
data class TutorialGraph(
    val nodes: List<TutorialNode>,
    val edges: List<TutorialEdge>,
    val evidence: List<TutorialEvidence> = emptyList(),
    val clusters: List<TutorialCluster> = emptyList(),
)

@Serializable
data class TutorialDiagram(val id: String, val graph: TutorialGraph, val revision: Int = 0)

@Serializable
data class SimulationStep(val highlightLines: List<Int>, val caption: String, val stdout: String = "")

@Serializable
data class SimulationScenario(val id: String, val label: String, val steps: List<SimulationStep>)

@Serializable
data class TutorialSimulation(
    val id: String,
    val language: String,
    val code: String,
    val scenarios: List<SimulationScenario>,
    val revision: Int = 0,
)

@Serializable
data class TutorialRecord(
    val id: String,
    val revision: Int,
    val title: String,
    val markdown: String,
    val contextId: String,
    val sourceIds: List<String>,
    val createdAt: Long,
)

@Serializable
data class TutorialSource(
    val id: String,
    val path: String,
    val context: String,
    val focus: String,
    val start: Int,
    val end: Int,
    val version: String,
)

@Serializable
data class TutorialContext(
    val id: String,
    val question: String,
    val sourceIds: List<String>,
    val graphContextToken: String? = null,
    val materialId: String? = null,
    val selectedText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class TutorialAnnotation(
    val id: String,
    val materialId: String,
    val kind: String,
    val start: Int,
    val end: Int,
    val quote: String,
    val text: String = "",
    val status: String = "draft",
    val answer: String = "",
    val contextId: String? = null,
    val answerMaterialId: String? = null,
    val parentId: String? = null,
    val orphaned: Boolean = false,
    val relocatable: Boolean = false,
)

@Serializable
data class TutorialReadingState(
    val caret: Int = 0,
    val scroll: Int = 0,
    val mode: TutorialDisplayMode = TutorialDisplayMode.PREVIEW,
    val previewSource: Int = 0,
    val divider: Double = 0.5,
    val diagrams: Map<String, TutorialViewport> = emptyMap(),
)

@Serializable
enum class TutorialDisplayMode { EDIT, PREVIEW, SPLIT }

@Serializable
data class TutorialViewport(val zoom: Double, val x: Double, val y: Double)

@Serializable
data class TutorialPosition(val x: Int, val y: Int)

@Serializable
data class TutorialResourceState(
    val diagrams: List<TutorialDiagram> = emptyList(),
    val simulations: List<TutorialSimulation> = emptyList(),
    val positions: Map<String, Map<String, TutorialPosition>> = emptyMap(),
)

@Serializable
data class TutorialEdit(val find: String, val replace: String)

/** 可恢复的输入错误包含字段位置，版本冲突附带当前版本。 */
class TutorialProblem(val code: String, val field: String, message: String, val currentRevision: Int? = null) : IllegalArgumentException(message)

internal fun tutorialRequire(condition: Boolean, code: String, field: String, message: String) {
    if (!condition) throw TutorialProblem(code, field, message)
}
