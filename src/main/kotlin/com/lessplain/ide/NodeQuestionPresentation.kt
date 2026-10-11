package com.lessplain.ide

import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingQuestion
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.ui.questionEditorTitle

/** 节点问题的预览只取该问题的上下文，不跟随画布当前选择或最新源码。 */
internal data class NodeQuestionPresentation(val title: String, val anchor: SourceAnchor?)

internal fun nodeQuestionPresentation(
    repository: UnderstandingRepository,
    recordId: String,
    nodeId: String,
    question: UnderstandingQuestion,
): NodeQuestionPresentation {
    val context = repository.getContext(question.contextToken)
    check(context.recordId == recordId && context.nodeId == nodeId) { "问题不属于当前节点" }
    val record = repository.getRecord(recordId)
    val node = record.graph.nodes.firstOrNull { it.id == nodeId } ?: error("节点已不存在")
    return NodeQuestionPresentation(
        questionEditorTitle(node.label),
        context.anchor?.takeIf { it.text.isNotBlank() },
    )
}
