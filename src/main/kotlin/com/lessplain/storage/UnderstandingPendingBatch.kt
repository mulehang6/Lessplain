package com.lessplain.storage

import com.lessplain.model.UnderstandingContext
import com.lessplain.model.UnderstandingInputException
import com.lessplain.model.UnderstandingQuestion
import com.lessplain.model.UnderstandingRecord

data class PendingNoteItem(val questionId: String, val question: String, val context: UnderstandingContext)
data class PendingNoteBatch(val scopeRecordId: String, val items: List<PendingNoteItem>, val records: List<UnderstandingRecord>)

/** 在同一读取快照中取得本记录及后代的待答注释，不隐式读取整个项目队列。 */
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
            PENDING_NOTE_SCOPE_SQL + "SELECT q.data, c.data, v.data " +
                PENDING_NOTE_FROM_SQL + "ORDER BY q.rowid",
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
