package com.lessplain.storage

import com.lessplain.model.UnderstandingQuestion
import com.lessplain.model.UnderstandingRecord

/** 一次读取快照中的总数与一页数据；UI 的最近记录上限不影响 MCP 翻页。 */
internal data class RepositoryPage<T>(val items: List<T>, val total: Int)

internal fun UnderstandingRepository.recordsPage(query: String, offset: Int, limit: Int): RepositoryPage<UnderstandingRecord> {
    val normalized = query.trim()
    val pattern = "%$normalized%"
    val from = "records r JOIN record_versions v ON v.record_id=r.id AND v.revision=r.current_revision"
    val where = "(?='' OR v.question_text LIKE ? OR v.display_title LIKE ? OR EXISTS " +
        "(SELECT 1 FROM questions q WHERE q.owner_record_id=r.id AND q.text LIKE ?))"
    return readPage(from, where, listOf(normalized, pattern, pattern, pattern), "v.data", "r.created_at DESC, r.id DESC", offset, limit) { rows ->
        gson.readUnderstandingRecord(rows.getString(1))
    }
}

internal fun UnderstandingRepository.questionsPage(
    materialId: String?, status: String, feedbackOnly: Boolean, offset: Int, limit: Int,
): RepositoryPage<UnderstandingQuestion> {
    val where = "(? IS NULL OR q.owner_record_id=?) AND (?='' OR q.status=?)" +
        if (feedbackOnly) " AND EXISTS (SELECT 1 FROM contexts c WHERE c.token=q.context_token AND json_extract(c.data, '$.nodeId') IS NOT NULL)" else ""
    return readPage("questions q", where, listOf(materialId, materialId, status, status),
        "q.data, q.owner_record_id, q.status", "q.rowid DESC", offset, limit) { rows ->
        gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java).copy(ownerRecordId = rows.getString(2), status = rows.getString(3))
    }
}

private fun <T> UnderstandingRepository.readPage(
    from: String, where: String, parameters: List<String?>, columns: String, order: String,
    offset: Int, limit: Int, read: (java.sql.ResultSet) -> T,
): RepositoryPage<T> = connect().use { db ->
    db.autoCommit = false
    val total = db.prepareStatement("SELECT COUNT(*) FROM $from WHERE $where").use { statement ->
        parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
    }
    val items = if (limit == 0) emptyList() else db.prepareStatement(
        "SELECT $columns FROM $from WHERE $where ORDER BY $order LIMIT ? OFFSET ?",
    ).use { statement ->
        parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.setInt(parameters.size + 1, limit)
        statement.setInt(parameters.size + 2, offset)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
    }
    db.commit()
    RepositoryPage(items, total)
}
