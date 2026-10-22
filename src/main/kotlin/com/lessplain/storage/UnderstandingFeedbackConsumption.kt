package com.lessplain.storage

import com.lessplain.model.requireInput
import java.sql.Connection

/** 在调用方事务内清理反馈，使删除与工具回执一同提交或回滚。 */
internal fun UnderstandingRepository.consumeReadFeedback(db: Connection, items: List<PendingNoteItem>) {
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
