package com.lessplain.storage

import com.lessplain.model.UnderstandingInputException
import com.lessplain.model.UnderstandingQuestion
import java.sql.Connection

/** 从回答图还原已回答问题，不额外保留注释行。 */
internal fun UnderstandingRepository.readQuestion(db: Connection, id: String): UnderstandingQuestion =
    readQuestions(db, "q.id=?", id).firstOrNull()
        ?: answerQuestions(db, "json_extract(v.data, '$.questionId')=?", id).firstOrNull()
        ?: throw UnderstandingInputException("questionId 不存在")

internal fun UnderstandingRepository.readRecordQuestions(db: Connection, recordId: String): List<UnderstandingQuestion> =
    (readQuestions(db, "q.owner_record_id=? OR EXISTS (SELECT 1 FROM records child JOIN record_versions v " +
        "ON v.record_id=child.id AND v.revision=child.current_revision WHERE child.parent_id=? " +
        "AND json_extract(v.data, '$.questionId')=q.id)", recordId, recordId) +
        answerQuestions(db, "r.parent_id=?", recordId)).distinctBy { it.id }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))

internal fun UnderstandingRepository.readContextQuestions(db: Connection, token: String): List<UnderstandingQuestion> =
    (readQuestions(db, "q.context_token=?", token) +
        answerQuestions(db, "json_extract(v.data, '$.contextToken')=?", token)).distinctBy { it.id }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))

private fun UnderstandingRepository.readQuestions(db: Connection, where: String,
    vararg parameters: String): List<UnderstandingQuestion> = db.prepareStatement(
    "SELECT q.data, q.owner_record_id, q.status FROM questions q WHERE $where ORDER BY q.rowid",
).use { statement ->
    parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
    statement.executeQuery().use { rows -> buildList {
        while (rows.next()) add(gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java).copy(
            ownerRecordId = rows.getString(2), status = rows.getString(3),
        ))
    } }
}

private fun UnderstandingRepository.answerQuestions(db: Connection, where: String,
    vararg parameters: String): List<UnderstandingQuestion> = db.prepareStatement(
    "SELECT v.data FROM records r JOIN record_versions v ON v.record_id=r.id AND v.revision=r.current_revision " +
        "WHERE json_extract(v.data, '$.questionId') IS NOT NULL AND $where " +
        "AND NOT EXISTS (SELECT 1 FROM questions q WHERE q.id=json_extract(v.data, '$.questionId')) " +
        "ORDER BY r.created_at, r.id",
).use { statement ->
    parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
    statement.executeQuery().use { rows -> buildList {
        while (rows.next()) {
            val record = gson.readUnderstandingRecord(rows.getString(1))
            val token = record.contextToken ?: continue
            add(UnderstandingQuestion(requireNotNull(record.questionId), record.question, token,
                record.parentId, "answered", record.createdAt))
        }
    } }
}
