package com.lessplain.core

import com.lessplain.model.UnderstandingRecord
import com.lessplain.storage.PendingNoteBatch
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.storage.pendingNoteBatch
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

internal data class PendingQuestionCopy(val text: String, val count: Int)

/** 点击时读取当前批次；生成概览不捕获新上下文，也不修改注释状态。 */
internal fun UnderstandingRepository.preparePendingQuestionCopy(scopeRecordId: String): PendingQuestionCopy {
    val root = getRecord(scopeRecordId)
    check(root.parentId == null) { "待答范围必须是理解根记录" }
    return formatPendingQuestionCopy(root, pendingNoteBatch(scopeRecordId))
}

/** 只取可读标题和原问题，不把上下文、源码依据或协议载荷拼进剪贴板。 */
internal fun formatPendingQuestionCopy(root: UnderstandingRecord, batch: PendingNoteBatch): PendingQuestionCopy {
    require(root.id == batch.scopeRecordId) { "待答范围与理解根记录不一致" }
    val records = batch.records.associateBy { it.id }
    val text = buildString {
        append("请用 Lessplain 处理「").append((root.displayTitle ?: root.question).singleLine())
        append("」的待答注释：\n\n")
        batch.items.forEachIndexed { index, item ->
            val record = records[item.context.recordId]
            append(index + 1).append(". ")
            if (item.context.recordId != root.id) {
                append((record?.displayTitle ?: record?.question ?: "来源图已失效").singleLine()).append(" / ")
            }
            append(record?.graph?.nodes?.firstOrNull { it.id == item.context.nodeId }?.label?.singleLine()
                ?: "来源节点已失效")
            append("：").append(item.question).append('\n')
        }
        append("\nLessplain 待答批次：").append(batch.scopeRecordId)
    }
    return PendingQuestionCopy(text, batch.items.size)
}

private fun String.singleLine(): String = replace(Regex("\\s+"), " ").trim()

/** 读取和格式化先完成；写入失败时恢复原剪贴板，包括非文本内容。 */
internal fun replaceQuestionClipboard(
    copy: PendingQuestionCopy,
    read: () -> Transferable?,
    write: (Transferable) -> Unit,
) {
    val previous = read()
    try {
        write(StringSelection(copy.text))
    } catch (failure: Exception) {
        if (previous != null) {
            try { write(previous) } catch (restoreFailure: Exception) { failure.addSuppressed(restoreFailure) }
        }
        throw failure
    }
}
