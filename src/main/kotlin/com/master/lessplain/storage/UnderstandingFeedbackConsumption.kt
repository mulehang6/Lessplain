package com.master.lessplain.storage

import com.master.lessplain.model.requireInput

/** 清理 Agent 本次读取的缺陷反馈，不生成回答、处理记录或取消历史。 */
internal fun UnderstandingRepository.consumeReadFeedback(items: List<PendingNoteItem>) = write { db ->
    items.forEach { item ->
        db.prepareStatement("DELETE FROM questions WHERE id=? AND context_token=? AND text=? AND status='pending'").use { statement ->
            statement.setString(1, item.questionId)
            statement.setString(2, item.context.token)
            statement.setString(3, item.question)
            requireInput(statement.executeUpdate() == 1, "反馈在读取期间已修改或移除，请重新读取")
        }
    }
    // 只清理失去引用的固定来源，仍被源码追问或已保存图使用的来源必须保留。
    items.map { it.context.token }.distinct().forEach { token ->
        db.prepareStatement(
            "DELETE FROM contexts WHERE token=? AND NOT EXISTS " +
                "(SELECT 1 FROM questions WHERE context_token=?) AND NOT EXISTS " +
                "(SELECT 1 FROM record_versions WHERE json_extract(data, '$.contextToken')=?)",
        ).use { statement ->
            statement.setString(1, token)
            statement.setString(2, token)
            statement.setString(3, token)
            statement.executeUpdate()
        }
    }
}
