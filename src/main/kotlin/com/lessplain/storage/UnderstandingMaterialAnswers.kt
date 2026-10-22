package com.lessplain.storage

import com.lessplain.model.UnderstandingAnswer
import com.lessplain.model.requireInput
import java.sql.Connection

/** 独立答复已有问题；校验、活动答案查重与写入共享同一个写事务。 */
fun UnderstandingRepository.answerMaterialQuestion(questionId: String, markdown: String, materialId: String?): UnderstandingAnswer {
    val text = markdown + (materialId?.let { "\n\n[打开相关产物](lessplain://material/$it)" } ?: "")
    return write { db ->
        val question = readQuestion(db, questionId)
        requireInput(question.status in setOf("pending", "answered"), "问题尚未提交或已取消")
        val active = readActiveAnswers(db, questionId)
        active.firstOrNull { it.answer == text } ?: UnderstandingAnswer(
            newId(), questionId, text, emptyList(), System.currentTimeMillis(), active.lastOrNull()?.id,
        ).also { insertAnswer(db, it) }
    }
}

/** 只在当前连接查询未被替代的答复，供读取和原子答复共用。 */
internal fun UnderstandingRepository.readActiveAnswers(db: Connection, questionId: String): List<UnderstandingAnswer> =
    db.prepareStatement(
        "SELECT a.data FROM answers a WHERE a.question_id=? " +
            "AND NOT EXISTS (SELECT 1 FROM answers newer WHERE json_extract(newer.data, '$.supersedesId')=a.id) ORDER BY a.rowid",
    ).use { statement ->
        statement.setString(1, questionId)
        statement.executeQuery().use { rows -> buildList {
            while (rows.next()) add(gson.fromJson(rows.getString(1), UnderstandingAnswer::class.java))
        } }
    }
