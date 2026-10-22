package com.lessplain.storage

import com.lessplain.model.UnderstandingAnswer
import com.lessplain.model.requireInput

/** 独立答复已有问题，创建图或教程本身不再消费问题。 */
fun UnderstandingRepository.answerMaterialQuestion(questionId: String, markdown: String, materialId: String?): UnderstandingAnswer {
    val text = markdown + (materialId?.let { "\n\n[打开相关产物](lessplain://material/$it)" } ?: "")
    val question = getQuestion(questionId)
    requireInput(question.status in setOf("pending", "answered"), "问题尚未提交或已取消")
    answersForQuestion(questionId).firstOrNull { it.answer == text }?.let { return it }
    return write { db ->
        val answer = UnderstandingAnswer(newId(), questionId, text, emptyList(), System.currentTimeMillis(), answersForQuestion(questionId).lastOrNull()?.id)
        db.prepareStatement("INSERT INTO answers(id, question_id, answer_text, data) VALUES(?, ?, ?, ?)").use { statement ->
            statement.setString(1, answer.id); statement.setString(2, questionId); statement.setString(3, answer.answer); statement.setString(4, gson.toJson(answer)); statement.executeUpdate()
        }
        db.prepareStatement("UPDATE questions SET status='answered', data=? WHERE id=?").use { statement ->
            statement.setString(1, gson.toJson(question.copy(status = "answered"))); statement.setString(2, questionId); statement.executeUpdate()
        }
        answer
    }
}
