package com.lessplain.storage

import com.lessplain.model.UnderstandingInputException
import java.sql.Connection

data class PendingNoteScope(val recordId: String, val count: Int)

/** 按持久化父链找到真正的根，再只计数；不读取图、代码快照或问题正文。 */
fun UnderstandingRepository.pendingNoteScope(recordId: String): PendingNoteScope = connect().use { db ->
    db.autoCommit = false
    try {
        val visited = mutableSetOf<String>()
        var current = recordId
        while (true) {
            if (!visited.add(current)) throw UnderstandingInputException("理解记录的父级关系存在循环")
            val parent = db.prepareStatement("SELECT parent_id FROM records WHERE id=?").use { statement ->
                statement.setString(1, current)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) throw UnderstandingInputException("理解记录不存在")
                    rows.getString(1)
                }
            }
            if (parent == null) return@use PendingNoteScope(current, db.countPendingNotes(current))
            current = parent
        }
        @Suppress("UNREACHABLE_CODE")
        error("无法找到理解根记录")
    } finally { db.rollback() }
}

private fun Connection.countPendingNotes(scopeRecordId: String): Int =
    prepareStatement(
        PENDING_NOTE_SCOPE_SQL + "SELECT COUNT(*) " + PENDING_NOTE_FROM_SQL,
    ).use { statement ->
        statement.setString(1, scopeRecordId)
        statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
    }

// 完整读取和轻量计数共用范围及筛选规则，失效节点的待答问题也属于该范围。
internal const val PENDING_NOTE_SCOPE_SQL =
    "WITH RECURSIVE scope(id) AS (SELECT id FROM records WHERE id=? UNION " +
        "SELECT r.id FROM records r JOIN scope s ON r.parent_id=s.id) "
internal const val PENDING_NOTE_FROM_SQL =
    "FROM scope s JOIN contexts c ON json_extract(c.data, '$.recordId')=s.id " +
        "JOIN questions q ON q.context_token=c.token JOIN records r ON r.id=s.id " +
        "JOIN record_versions v ON v.record_id=r.id AND v.revision=r.current_revision " +
        "WHERE q.status='pending' AND json_extract(c.data, '$.nodeId') IS NOT NULL "
