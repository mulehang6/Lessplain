package com.master.understanding.storage

import com.master.understanding.model.UnderstandingContext
import com.master.understanding.model.UnderstandingInputException
import com.master.understanding.model.UnderstandingQuestion
import com.master.understanding.model.UnderstandingRecord

data class PendingNoteItem(val questionId: String, val question: String, val context: UnderstandingContext)
data class PendingNoteBatch(val scopeRecordId: String, val items: List<PendingNoteItem>, val records: List<UnderstandingRecord>)

/** One read snapshot of pending notes within this record and descendants, never an implicit project-wide queue. */
fun UnderstandingRepository.pendingNoteBatch(scopeRecordId: String): PendingNoteBatch = connect().use { db ->
    db.autoCommit = false
    try {
        db.prepareStatement("SELECT 1 FROM records WHERE id=?").use { statement ->
            statement.setString(1, scopeRecordId)
            statement.executeQuery().use { if (!it.next()) throw UnderstandingInputException("理解记录不存在") }
        }
        val items = mutableListOf<PendingNoteItem>()
        val records = linkedMapOf<String, UnderstandingRecord>()
        db.prepareStatement(
            "WITH RECURSIVE scope(id) AS (SELECT id FROM records WHERE id=? UNION " +
                "SELECT r.id FROM records r JOIN scope s ON r.parent_id=s.id) " +
                "SELECT q.data, c.data, v.data FROM scope s JOIN contexts c " +
                "ON json_extract(c.data, '$.recordId')=s.id JOIN questions q ON q.context_token=c.token " +
                "JOIN records r ON r.id=s.id JOIN record_versions v " +
                "ON v.record_id=r.id AND v.revision=r.current_revision " +
                "WHERE q.status='pending' AND json_extract(c.data, '$.nodeId') IS NOT NULL ORDER BY q.rowid",
        ).use { statement ->
            statement.setString(1, scopeRecordId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val question = gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java)
                    val context = gson.fromJson(rows.getString(2), UnderstandingContext::class.java)
                    val record = gson.readUnderstandingRecord(rows.getString(3)).copy(answer = "")
                    items += PendingNoteItem(question.id, question.text, context)
                    records[record.id] = record
                }
            }
        }
        PendingNoteBatch(scopeRecordId, items, records.values.toList())
    } finally { db.rollback() }
}
