package com.lessplain.storage

import com.lessplain.model.UnderstandingQuestion
import com.lessplain.model.requireInput

/** 取消保留引用的上下文快照，不能删除已回答的子图。 */
fun UnderstandingRepository.cancelPendingQuestion(questionId: String) = write { db ->
    db.prepareStatement("UPDATE questions SET status='cancelled' WHERE id=? AND status='pending'").use { statement ->
        statement.setString(1, questionId)
        requireInput(statement.executeUpdate() == 1, "注释已回答、已取消或不存在，请刷新")
    }
}

/** 待答节点注释复用已有问题行和上下文快照。 */
fun UnderstandingRepository.pendingQuestionsForNode(recordId: String, nodeId: String): List<UnderstandingQuestion> =
    connect().use { db ->
        val result = mutableListOf<UnderstandingQuestion>()
        db.prepareStatement(
            "SELECT q.data, q.owner_record_id, q.status FROM questions q " +
                "JOIN contexts c ON c.token=q.context_token " +
                "WHERE q.status='pending' AND json_extract(c.data, '$.recordId')=? " +
                "AND json_extract(c.data, '$.nodeId')=? ORDER BY q.rowid",
        ).use { statement ->
            statement.setString(1, recordId)
            statement.setString(2, nodeId)
            statement.executeQuery().use { rows ->
                while (rows.next()) result += gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java).copy(
                    ownerRecordId = rows.getString(2), status = rows.getString(3),
                )
            }
        }
        result
    }
