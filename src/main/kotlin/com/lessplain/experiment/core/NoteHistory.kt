package com.lessplain.experiment.core

import com.lessplain.experiment.model.ContextTicket
import com.lessplain.experiment.model.ViewNote
import com.lessplain.experiment.model.ViewRecord
import com.lessplain.experiment.storage.ViewStore

/** 待答行与答案快照的迁移和引用重建。只在存储事务内操作，不改写冻结的用户选区。 */
internal object NoteHistory {
    fun find(transaction: ViewStore.Transaction, store: ViewStore, ref: String): ViewNote? = with(transaction) {
        get("notes", baseRef(ref))?.let { return store.json.decodeFromString<ViewNote>(it) }
        all("views").values.asSequence().map { store.json.decodeFromString<ViewRecord>(it) }
            .firstOrNull { it.answeredNote?.let { note -> baseRef(note.ref) == baseRef(ref) } == true }
            ?.let { answer -> answer.answeredNote?.copy(status = "answered", answerRef = answer.ref) }
    }

    fun removeUncommittedContexts(transaction: ViewStore.Transaction, store: ViewStore, ref: String) = with(transaction) {
        all("contexts").forEach { (key, value) ->
            val ticket = store.json.decodeFromString<ContextTicket>(value)
            if (ticket.noteRef?.let(::baseRef) == baseRef(ref) && get("commits", key) == null) remove("contexts", key)
        }
    }

    /** 答案先归档再删行；异常会回滚整个升级，避免丢失无法归档的问题。可重复执行。 */
    fun upgrade(store: ViewStore) = store.transaction {
        all("notes").forEach { (key, value) ->
            val note = store.json.decodeFromString<ViewNote>(value)
            when (note.status) {
                "answered" -> {
                    val answer = note.answerRef?.let { get("views", it) }?.let { store.json.decodeFromString<ViewRecord>(it) }
                    // 旧版单独删除回答后可能留下孤立已答行；保留问题并重新待答，不能丢原文或阻断全部历史。
                    if (answer == null) {
                        put("notes", key, store.json.encodeToString(note.copy(status = "pending", answerRef = null)))
                        return@forEach
                    }
                    check(answer.answeredNote == null || answer.answeredNote.ref == note.ref) { "回答的问题快照冲突" }
                    put("views", answer.ref, store.json.encodeToString(answer.copy(answeredNote = answer.answeredNote ?: note)))
                    remove("notes", key)
                }
                "cancelled" -> remove("notes", key)
            }
        }
        all("contexts").forEach { (key, value) ->
            if (get("commits", key) == null) {
                val ticket = store.json.decodeFromString<ContextTicket>(value)
                val note = ticket.noteRef?.let { find(this, store, it) }
                val target = ticket.targetRef?.let { get("views", it) }?.let { store.json.decodeFromString<ViewRecord>(it) }
                val invalidNote = ticket.noteRef != null && (note == null || note.ref != ticket.noteRef ||
                    note.revision != ticket.noteRevision || note.status == "cancelled" ||
                    (note.kind == "question" && note.answerRef != ticket.targetRef))
                if (invalidNote || (ticket.targetRef != null && target?.revision != ticket.expectedRevision) ||
                    (ticket.parentRef != null && get("views", ticket.parentRef) == null)
                ) remove("contexts", key)
            }
        }
    }
}
