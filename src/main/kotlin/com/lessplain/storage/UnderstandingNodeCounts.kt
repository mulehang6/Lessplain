package com.lessplain.storage

import java.sql.Connection

internal data class NodeQuestionKey(val recordId: String, val nodeId: String)
internal data class NodeQuestionCounts(
    val pending: Map<NodeQuestionKey, Int>,
    val answered: Map<NodeQuestionKey, Int>,
    val revisions: Map<String, Int>,
)

/** 一个连接中分组计数，不为每个节点打开连接或反序列化问题、图和源码快照。 */
internal fun UnderstandingRepository.nodeQuestionCounts(recordIds: Collection<String>): NodeQuestionCounts {
    if (recordIds.isEmpty()) return NodeQuestionCounts(emptyMap(), emptyMap(), emptyMap())
    return connect().use { db ->
        db.autoCommit = false
        try {
            val pending = linkedMapOf<NodeQuestionKey, Int>()
            val answered = linkedMapOf<NodeQuestionKey, Int>()
            val revisions = linkedMapOf<String, Int>()
            recordIds.distinct().chunked(200).forEach { ids ->
                val placeholders = ids.joinToString(",") { "?" }
                db.readNodeCounts(
                    "SELECT json_extract(c.data, '$.recordId'), json_extract(c.data, '$.nodeId'), COUNT(*) " +
                        "FROM questions q JOIN contexts c ON c.token=q.context_token " +
                        "WHERE q.status='pending' AND json_extract(c.data, '$.recordId') IN ($placeholders) " +
                        "AND json_extract(c.data, '$.nodeId') IS NOT NULL GROUP BY 1, 2",
                    ids, pending,
                )
                // 已答问题行可能已被删除；从当前回答图读取 questionId 和固定 token。
                db.readNodeCounts(
                    "WITH targets(id) AS (VALUES ${ids.joinToString(",") { "(?)" }}) " +
                        "SELECT t.id, json_extract(c.data, '$.nodeId'), " +
                        "COUNT(DISTINCT json_extract(v.data, '$.questionId')) " +
                        "FROM targets t JOIN records r JOIN record_versions v " +
                        "ON v.record_id=r.id AND v.revision=r.current_revision " +
                        "LEFT JOIN questions q ON q.id=json_extract(v.data, '$.questionId') " +
                        "JOIN contexts c ON c.token=COALESCE(q.context_token, json_extract(v.data, '$.contextToken')) " +
                        "WHERE json_extract(v.data, '$.questionId') IS NOT NULL " +
                        "AND (r.parent_id=t.id OR q.owner_record_id=t.id) " +
                        "AND json_extract(c.data, '$.nodeId') IS NOT NULL GROUP BY 1, 2",
                    ids, answered,
                )
                db.prepareStatement("SELECT id, current_revision FROM records WHERE id IN ($placeholders)").use { statement ->
                    ids.forEachIndexed { index, id -> statement.setString(index + 1, id) }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) revisions[rows.getString(1)] = rows.getInt(2)
                    }
                }
            }
            NodeQuestionCounts(pending, answered, revisions)
        } finally { db.rollback() }
    }
}

private fun Connection.readNodeCounts(sql: String, ids: List<String>, target: MutableMap<NodeQuestionKey, Int>) {
    prepareStatement(sql).use { statement ->
        ids.forEachIndexed { index, id -> statement.setString(index + 1, id) }
        statement.executeQuery().use { rows ->
            while (rows.next()) target[NodeQuestionKey(rows.getString(1), rows.getString(2))] = rows.getInt(3)
        }
    }
}
