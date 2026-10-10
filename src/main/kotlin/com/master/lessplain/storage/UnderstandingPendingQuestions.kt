package com.master.lessplain.storage

import com.master.lessplain.model.UnderstandingQuestion
import com.master.lessplain.model.requireInput

/** Cancellation keeps referenced context snapshots and cannot remove an answered child graph. */
fun UnderstandingRepository.cancelPendingQuestion(questionId: String) = write { db ->
    db.prepareStatement("UPDATE questions SET status='cancelled' WHERE id=? AND status='pending'").use { statement ->
        statement.setString(1, questionId)
        requireInput(statement.executeUpdate() == 1, "注释已回答、已取消或不存在，请刷新")
    }
}

/** Pending node notes reuse the existing question rows and context snapshots. */
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

fun UnderstandingRepository.pendingQuestions(): List<UnderstandingQuestion> = connect().use { db ->
    val result = mutableListOf<UnderstandingQuestion>()
    db.prepareStatement("SELECT data, owner_record_id, status FROM questions WHERE status='pending' ORDER BY rowid").use { statement ->
        statement.executeQuery().use { rows ->
            while (rows.next()) result += gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java).copy(
                ownerRecordId = rows.getString(2), status = rows.getString(3),
            )
        }
    }
    result
}
