package com.lessplain.storage

import com.lessplain.model.UnderstandingContext
import com.lessplain.model.UnderstandingRecord
import com.lessplain.model.requireInput
import java.sql.Connection

/** 提交前复核来源，防止准备图期间父图或冻结上下文已被删除。调用者持有写事务。 */
internal fun UnderstandingRepository.validateRecordOrigin(db: Connection, record: UnderstandingRecord) {
    record.parentId?.let { parentId ->
        db.prepareStatement("SELECT 1 FROM records WHERE id=?").use { statement ->
            statement.setString(1, parentId)
            statement.executeQuery().use { rows -> requireInput(rows.next(), "父理解记录已删除") }
        }
    }
    record.contextToken?.let { token ->
        db.prepareStatement("SELECT project_id FROM contexts WHERE token=?").use { statement ->
            statement.setString(1, token)
            statement.executeQuery().use { rows ->
                requireInput(rows.next() && rows.getString(1) == projectId, "contextToken 不存在、已失效或属于其他项目")
            }
        }
    }
    record.questionId?.let { questionId ->
        db.prepareStatement("SELECT context_token FROM questions WHERE id=?").use { statement ->
            statement.setString(1, questionId)
            statement.executeQuery().use { rows ->
                requireInput(rows.next() && rows.getString(1) == record.contextToken, "待答注释的冻结来源已变化")
            }
        }
    }
}

/** 捕获与删除、提问与删除都可能交错；来源存活校验必须与插入共用写事务。 */
internal fun UnderstandingRepository.validateContextOrigin(db: Connection, context: UnderstandingContext) {
    requireInput(context.projectId == projectId, "上下文属于其他项目")
    context.recordId?.let { recordId ->
        db.prepareStatement("SELECT 1 FROM records WHERE id=?").use { statement ->
            statement.setString(1, recordId)
            statement.executeQuery().use { rows -> requireInput(rows.next(), "上下文的来源记录已删除") }
        }
    }
}
