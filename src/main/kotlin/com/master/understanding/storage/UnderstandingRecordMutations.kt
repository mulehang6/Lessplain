package com.master.understanding.storage

import com.master.understanding.model.UnderstandingInputException
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingQuestion
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.model.requireInput
import java.sql.Connection

internal data class DeletedUnderstanding(val recordIds: Set<String>, val questionIds: Set<String>)

/** Replaces only a graph while retaining record identity, history, and attached questions. */
internal fun UnderstandingRepository.reviseRecordGraph(
    db: Connection,
    recordId: String,
    expectedRevision: Int,
    question: String,
    graph: UnderstandingGraph,
): UnderstandingRecord {
    val current = db.prepareStatement(
        "SELECT v.data FROM records r JOIN record_versions v ON v.record_id=r.id " +
            "AND v.revision=r.current_revision WHERE r.id=?",
    ).use { statement ->
        statement.setString(1, recordId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) throw UnderstandingInputException("理解记录不存在")
            gson.fromJson(rows.getString(1), UnderstandingRecord::class.java)
        }
    }
    requireInput(current.revision == expectedRevision, "理解记录版本已变化，请重新读取")
    requireInput(current.question == question, "修订不能更改原问题")
    requireInput(graph.nodes.map { it.id } == current.graph.nodes.map { it.id }, "修订须保留节点 ID 与顺序")
    requireInput(graph.edges.map { it.id } == current.graph.edges.map { it.id }, "修订须保留关系 ID 与顺序")
    requireInput(graph.edges.map { Triple(it.from, it.to, it.relation) } ==
        current.graph.edges.map { Triple(it.from, it.to, it.relation) }, "修订须保留节点关系")
    val revised = current.copy(revision = current.revision + 1, graph = graph)
    db.prepareStatement(
        "INSERT INTO record_versions(record_id, revision, question_text, answer_text, data, display_title) " +
            "VALUES(?, ?, ?, ?, ?, ?)",
    ).use { statement ->
        statement.setString(1, recordId)
        statement.setInt(2, revised.revision)
        statement.setString(3, revised.question)
        statement.setString(4, revised.answer)
        statement.setString(5, gson.toJson(revised))
        statement.setString(6, revised.displayTitle)
        statement.executeUpdate()
    }
    db.prepareStatement("UPDATE records SET current_revision=? WHERE id=? AND current_revision=?").use { statement ->
        statement.setInt(1, revised.revision)
        statement.setString(2, recordId)
        statement.setInt(3, expectedRevision)
        requireInput(statement.executeUpdate() == 1, "理解记录版本已变化，请重新读取")
    }
    return revised
}

/** Title revisions keep the original question and graph untouched. */
fun UnderstandingRepository.updateDisplayTitle(recordId: String, title: String): UnderstandingRecord {
    val normalized = title.trim()
    if (normalized.isEmpty()) throw UnderstandingInputException("标题不能为空")
    return write { db ->
        val original = db.prepareStatement(
            "SELECT v.data FROM records r JOIN record_versions v ON v.record_id=r.id " +
                "AND v.revision=r.current_revision WHERE r.id=?",
        ).use { statement ->
            statement.setString(1, recordId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw UnderstandingInputException("理解记录不存在")
                gson.fromJson(rows.getString(1), UnderstandingRecord::class.java)
            }
        }
        val updated = original.copy(revision = original.revision + 1, displayTitle = normalized)
        db.prepareStatement(
            "INSERT INTO record_versions(record_id, revision, question_text, answer_text, data, display_title) " +
                "VALUES(?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, recordId)
            statement.setInt(2, updated.revision)
            statement.setString(3, original.question)
            statement.setString(4, original.answer)
            statement.setString(5, gson.toJson(updated))
            statement.setString(6, normalized)
            statement.executeUpdate()
        }
        db.prepareStatement("UPDATE records SET current_revision=? WHERE id=?").use { statement ->
            statement.setInt(1, updated.revision)
            statement.setString(2, recordId)
            statement.executeUpdate()
        }
        updated
    }
}

/** A pending note is still a question, so editing it does not create parallel annotation storage. */
fun UnderstandingRepository.editPendingQuestion(questionId: String, text: String): UnderstandingQuestion {
    val normalized = text.trim()
    if (normalized.isEmpty()) throw UnderstandingInputException("注释不能为空")
    return write { db ->
        val current = db.prepareStatement("SELECT data, owner_record_id, status FROM questions WHERE id=?").use { statement ->
            statement.setString(1, questionId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw UnderstandingInputException("注释不存在")
                gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java).copy(
                    ownerRecordId = rows.getString(2), status = rows.getString(3),
                )
            }
        }
        if (current.status != "pending") throw UnderstandingInputException("已回答的注释不能修改")
        val updated = current.copy(text = normalized)
        db.prepareStatement("UPDATE questions SET text=?, data=? WHERE id=?").use { statement ->
            statement.setString(1, normalized)
            statement.setString(2, gson.toJson(updated))
            statement.setString(3, questionId)
            statement.executeUpdate()
        }
        updated
    }
}

internal fun UnderstandingRepository.deleteRootRecord(rootId: String): DeletedUnderstanding = write { db ->
    val isRoot = db.prepareStatement("SELECT parent_id FROM records WHERE id=?").use { statement ->
        statement.setString(1, rootId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) throw UnderstandingInputException("理解记录不存在")
            rows.getString(1) == null
        }
    }
    if (!isRoot) throw UnderstandingInputException("只能删除根记录")
    val records = db.prepareStatement(
        "WITH RECURSIVE tree(id) AS (SELECT id FROM records WHERE id=? UNION ALL " +
            "SELECT r.id FROM records r JOIN tree t ON r.parent_id=t.id) SELECT id FROM tree",
    ).use { statement ->
        statement.setString(1, rootId)
        statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
    }
    val contexts = db.selectIds("SELECT token FROM contexts WHERE json_extract(data, '$.recordId') IN (${marks(records)})", records)
    val questions = linkedSetOf<String>()
    questions += db.selectIds("SELECT id FROM questions WHERE owner_record_id IN (${marks(records)})", records)
    if (contexts.isNotEmpty()) questions += db.selectIds(
        "SELECT id FROM questions WHERE context_token IN (${marks(contexts)})", contexts,
    )
    questions += db.selectIds(
        "SELECT DISTINCT json_extract(data, '$.questionId') FROM record_versions " +
            "WHERE record_id IN (${marks(records)}) AND json_extract(data, '$.questionId') IS NOT NULL", records,
    )
    val answers = if (questions.isEmpty()) emptySet() else db.selectIds(
        "SELECT id FROM answers WHERE question_id IN (${marks(questions)})", questions,
    )
    tombstoneRequests(db, records, questions, answers)
    if (questions.isNotEmpty()) {
        db.deleteIds("DELETE FROM answers WHERE question_id IN (${marks(questions)})", questions)
        db.deleteIds("DELETE FROM questions WHERE id IN (${marks(questions)})", questions)
    }
    db.deleteIds("DELETE FROM browse_state WHERE record_id IN (${marks(records)})", records)
    db.deleteIds("DELETE FROM record_versions WHERE record_id IN (${marks(records)})", records)
    db.deleteIds("DELETE FROM records WHERE id IN (${marks(records)})", records)
    if (contexts.isNotEmpty()) {
        val retained = db.selectIds(
            "SELECT DISTINCT json_extract(data, '$.contextToken') FROM record_versions " +
                "WHERE json_extract(data, '$.contextToken') IN (${marks(contexts)})", contexts,
        )
        val removable = contexts - retained
        if (removable.isNotEmpty()) db.deleteIds(
            "DELETE FROM contexts WHERE token IN (${marks(removable)})", removable,
        )
    }
    DeletedUnderstanding(records, questions)
}

private fun tombstoneRequests(db: Connection, records: Set<String>, questions: Set<String>, answers: Set<String>) {
    val ids = mutableListOf<String>()
    db.createStatement().use { statement ->
        statement.executeQuery("SELECT request_id, response FROM requests").use { rows ->
            while (rows.next()) {
                val response = rows.getString(2)
                if (response == DELETED_REQUEST_RESPONSE) continue
                val data = runCatching { com.google.gson.JsonParser.parseString(response).asJsonObject }.getOrNull() ?: continue
                val id = data.get("id")?.takeUnless { it.isJsonNull }?.asString
                val questionId = data.get("questionId")?.takeUnless { it.isJsonNull }?.asString
                if (id in records || id in answers || questionId in questions) ids += rows.getString(1)
            }
        }
    }
    if (ids.isNotEmpty()) db.deleteIds(
        "UPDATE requests SET response=? WHERE request_id IN (${marks(ids)})", ids, DELETED_REQUEST_RESPONSE,
    )
}

private fun marks(ids: Collection<String>): String = ids.joinToString(",") { "?" }

private fun Connection.selectIds(sql: String, ids: Collection<String>): Set<String> = prepareStatement(sql).use { statement ->
    ids.forEachIndexed { index, id -> statement.setString(index + 1, id) }
    statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
}

private fun Connection.deleteIds(sql: String, ids: Collection<String>, prefix: String? = null) {
    prepareStatement(sql).use { statement ->
        if (prefix != null) statement.setString(1, prefix)
        ids.forEachIndexed { index, id -> statement.setString(index + 1 + if (prefix == null) 0 else 1, id) }
        statement.executeUpdate()
    }
}
