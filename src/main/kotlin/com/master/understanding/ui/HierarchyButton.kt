package com.master.understanding.ui

import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.storage.UnderstandingRepository

internal data class GraphHierarchyEntry(
    val title: String,
    val depth: Int,
    val layers: List<FollowUpLayer>,
)

/** Lists every reachable follow-up layer, including branches below the current depth. */
internal fun graphHierarchyEntries(
    repository: UnderstandingRepository,
    rootRecordId: String?,
): List<GraphHierarchyEntry> {
    val root = rootRecordId?.let { runCatching { repository.getRecord(it) }.getOrNull() }
        ?: return emptyList()
    val entries = mutableListOf(GraphHierarchyEntry(root.question, 0, emptyList()))

    fun visit(record: UnderstandingRecord, path: List<FollowUpLayer>, ancestors: Set<String>) {
        val questionsByNode = repository.questionsForRecord(record.id).groupBy { question ->
            runCatching { repository.getContext(question.contextToken).nodeId }.getOrNull()
        }
        record.graph.nodes.forEach { node ->
            val questions = questionsByNode[node.id].orEmpty()
            if (questions.isEmpty()) return@forEach
            val childPath = path + FollowUpLayer(record.id, node.id)
            entries += GraphHierarchyEntry("${node.label.replace('\n', ' ')} 的追问", childPath.size, childPath)
            questions.forEach { question ->
                val child = repository.recordForQuestion(question.id)
                if (child != null && child.id !in ancestors) {
                    visit(child, childPath, ancestors + child.id)
                }
            }
        }
    }

    visit(root, emptyList(), setOf(root.id))
    return entries
}
