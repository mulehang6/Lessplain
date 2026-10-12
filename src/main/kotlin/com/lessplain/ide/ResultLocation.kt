package com.lessplain.ide

import com.lessplain.core.cancellationAwareResult
import com.lessplain.model.UnderstandingRecord
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.ui.FollowUpLayer
import com.lessplain.ui.graphHierarchyEntries

/** 结果展示所需的关系和完整层级在后台解析，界面一次性应用。 */
internal data class ResultLocation(
    val record: UnderstandingRecord,
    val rootRecordId: String? = null,
    val layers: List<FollowUpLayer> = emptyList(),
    val questionId: String? = null,
)

internal fun loadResultLocation(repository: UnderstandingRepository, result: PendingResult): ResultLocation? {
    val question = result.questionId?.let { cancellationAwareResult { repository.getQuestion(it) }.getOrNull() }
    val context = question?.let { cancellationAwareResult { repository.getContext(it.contextToken) }.getOrNull() }
    val ownerId = question?.ownerRecordId ?: context?.recordId
    val nodeId = context?.nodeId
    if (ownerId != null && nodeId != null) {
        val owner = repository.getRecord(ownerId)
        var root = owner
        val visited = mutableSetOf<String>()
        while (root.parentId != null && visited.add(root.id)) root = repository.getRecord(requireNotNull(root.parentId))
        val layer = FollowUpLayer(owner.id, nodeId)
        val entry = graphHierarchyEntries(repository, root.id).firstOrNull { it.layers.lastOrNull() == layer }
        return ResultLocation(owner, root.id, entry?.layers ?: listOf(layer), question.id)
    }
    return result.recordId?.let { ResultLocation(repository.getRecord(it)) }
}
