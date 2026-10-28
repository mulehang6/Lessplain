package com.lessplain.tutorial

import com.google.gson.Gson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TutorialDeletionTest {
    @TempDir lateinit var root: Path
    @Test fun `deletion removes owned content and receipts while preserving references contexts and sources`() {
        lateinit var id: String
        lateinit var context: TutorialContext
        lateinit var otherId: String
        TutorialStore(root).use { store ->
            val source = store.saveSource(TutorialSource("src_shared", "Flow.kt", "Flow", "Flow", 0, 4, "hash"))
            context = store.saveContext(TutorialContext("ctx", "共享问题", listOf(source.id)))
            val first = store.create(context.id, "删除目标", "应彻底清理的正文", TutorialResourceState(), "create-key")
            id = first.id
            val other = store.create(context.id, "其他教程", "保留正文", TutorialResourceState())
            otherId = other.id
            store.createAnnotation(TutorialAnnotation("note_owned", id, "note", 0, 2, "原文", text = "应清理的笔记"))
            store.saveContext(TutorialContext("ctx_owned", "应清理的问题", emptyList(), materialId = id))
            store.saveContext(TutorialContext("ctx_unreferenced", "已无批注的上下文", emptyList(), materialId = id))
            store.createAnnotation(TutorialAnnotation("q_owned", id, "question", 0, 2, "原文", text = "问题", contextId = "ctx_owned"))
            store.createAnnotation(TutorialAnnotation("q_other", other.id, "question", 0, 2, "保留", text = "保留的问题", status = "answered", answerMaterialId = id))
            store.edit(id, "edit-key") {}
            store.saveToolReceipt("create-key", Gson().toJson(mapOf("ok" to true, "data" to mapOf("tutorial" to first, "resources" to TutorialResourceState()))))
            store.flush()
            store.deleteTutorial(id)
            assertEquals(listOf(other), store.list())
            assertEquals(id, store.annotation("q_other").answerMaterialId)
            assertEquals(context, store.context(context.id))
            assertEquals(source, store.source(source.id))
            assertFailsWith<TutorialProblem> { store.context("ctx_owned") }
            assertFailsWith<TutorialProblem> { store.context("ctx_unreferenced") }
            assertEquals("DELETED", assertFailsWith<TutorialProblem> { store.record(id) }.code)
            assertEquals("DELETED", assertFailsWith<TutorialProblem> { store.create(context.id, first.title, first.markdown, TutorialResourceState()) }.code)
            assertEquals("DELETED", assertFailsWith<TutorialProblem> { store.annotation("note_owned") }.code)
            assertTrue(requireNotNull(store.toolReceipt("create-key")).contains("DELETED"))
            assertNull(store.receipt("edit-key"))
            assertFailsWith<TutorialProblem> { store.saveReading(id, TutorialReadingState()) }
            assertFailsWith<TutorialProblem> { store.savePositions(id, "d", emptyMap()) }
            store.flush()
            assertFalse(Files.exists(root.resolve(id)))
            val json = Files.list(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".json") }.map { Files.readString(it) }.toList().joinToString() }
            assertFalse(json.contains("应彻底清理的正文")); assertFalse(json.contains("应清理的笔记")); assertFalse(json.contains("应清理的问题"))
        }
        TutorialStore(root).use { store ->
            assertTrue(store.isDeleted(id)); assertEquals(listOf(otherId), store.list().map { it.id })
            assertTrue(requireNotNull(store.toolReceipt("create-key")).contains("DELETED"))
        }
    }

    @Test fun `deduplication after manual editing remembers the new creation identity across deletion and reopen`() {
        TutorialStore(root).use { store ->
            store.saveContext(TutorialContext("ctx", "问题", emptyList()))
            val original = store.create("ctx", "标题", "原正文", TutorialResourceState())
            val edited = store.change(original.id, original.revision, "手改正文", 0, 3, 4)
            assertEquals(edited, store.create("ctx", "标题", edited.markdown, TutorialResourceState(), "edited-create"))
            store.flush()
            store.deleteTutorial(original.id)
            assertTrue(requireNotNull(store.toolReceipt("edited-create")).contains("DELETED"))
        }
        TutorialStore(root).use { store ->
            assertEquals("DELETED", assertFailsWith<TutorialProblem> {
                store.create("ctx", "标题", "手改正文", TutorialResourceState())
            }.code)
            assertTrue(requireNotNull(store.toolReceipt("edited-create")).contains("DELETED"))
        }
    }

    @Test fun `failed deletion registration keeps material available for retry`() {
        TutorialStore(root).use { store ->
            store.saveContext(TutorialContext("ctx", "问题", emptyList()))
            val record = store.create("ctx", "标题", "正文", TutorialResourceState()); store.flush()
            val blocked = root.resolve("lifetimes.json.tmp"); Files.createDirectory(blocked)
            assertEquals("PERSISTENCE_FAILED", assertFailsWith<TutorialProblem> { store.deleteTutorial(record.id) }.code)
            assertEquals(record, store.record(record.id)); assertFalse(store.isUnavailable(record.id))
            Files.delete(blocked)
            store.deleteTutorial(record.id)
            assertTrue(store.isDeleted(record.id))
        }
    }

    @Test fun `cleanup failure cannot revive material and next open completes cleanup`() {
        lateinit var record: TutorialRecord
        TutorialStore(root, cleanupDeleted = { _, _ -> throw java.io.IOException("清理失败") }).use { store ->
            store.saveContext(TutorialContext("ctx", "应清理的上下文", emptyList()))
            record = store.create("ctx", "标题", "正文", TutorialResourceState()); store.flush()
            assertEquals("CLEANUP_PENDING", assertFailsWith<TutorialProblem> { store.deleteTutorial(record.id) }.code)
            assertTrue(Files.exists(root.resolve(record.id)))
            assertTrue(store.list().isEmpty())
        }
        TutorialStore(root).use { store ->
            store.flush()
            assertTrue(store.list().isEmpty()); assertTrue(store.isDeleted(record.id))
            assertFalse(Files.exists(root.resolve(record.id)))
            assertFailsWith<TutorialProblem> { store.context("ctx") }
        }
    }
}
