package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TutorialPersistenceRecoveryTest {
    @TempDir lateinit var root: Path
    @Test fun `failed paths remain visible until repaired and flush retries the latest snapshot`() {
        TutorialStore(root).use { store ->
            val blocked = root.resolve("sources.json.tmp")
            Files.createDirectory(blocked)
            val first = TutorialSource("src_first", "Flow.kt", "Flow", "Flow", 0, 4, "hash")
            store.saveSource(first)
            assertEquals("PERSISTENCE_FAILED", assertFailsWith<TutorialProblem> { store.flush() }.code)
            store.saveContext(TutorialContext("ctx", "其他写入成功", emptyList()))
            assertFailsWith<TutorialProblem> { store.flush() }
            Files.delete(blocked)
            val second = first.copy(id = "src_second", focus = "Flo", end = 3)
            store.saveSource(second)
            store.flush()
            assertNull(store.writeFailure)
            TutorialStore(root).use { reader -> assertEquals(listOf(first, second), reader.sources()) }
        }
    }

    @Test fun `failed content transaction reopens wholly old and retry commits text annotations and receipt together`() {
        lateinit var original: TutorialRecord
        TutorialStore(root).use { store ->
            store.saveContext(TutorialContext("ctx", "问题", emptyList()))
            original = store.create("ctx", "标题", "前文 Flow 后文", TutorialResourceState())
            store.createAnnotation(TutorialAnnotation("note", original.id, "note", 3, 7, "Flow", text = "个人笔记"))
            store.flush()
            val blocked = root.resolve(original.id).resolve("content.json.tmp")
            Files.createDirectory(blocked)
            store.edit(original.id, "edit") { store.change(original.id, 1, "新增 前文 Flow 后文", 0, 0, 3) }
            assertFailsWith<TutorialProblem> { store.flush() }
            TutorialStore(root).use { reader ->
                assertEquals(original, reader.record(original.id)); assertNull(reader.receipt("edit"))
                assertEquals(3, reader.annotation("note").start)
            }
        }
        Files.delete(root.resolve(original.id).resolve("content.json.tmp"))
        TutorialStore(root).use { store ->
            store.edit(original.id, "edit") { store.change(original.id, 1, "新增 前文 Flow 后文", 0, 0, 3) }
            store.flush()
        }
        TutorialStore(root).use { store ->
            assertEquals("新增 前文 Flow 后文", store.record(original.id).markdown)
            assertEquals(store.record(original.id), store.receipt("edit"))
            assertEquals(6, store.annotation("note").start)
            assertEquals("个人笔记", store.annotation("note").text)
        }
    }

    @Test fun `delete discards failed content writes so later flush cannot recreate files`() {
        TutorialStore(root).use { store ->
            store.saveContext(TutorialContext("ctx", "问题", emptyList()))
            val record = store.create("ctx", "标题", "正文", TutorialResourceState()); store.flush()
            Files.createDirectory(root.resolve(record.id).resolve("content.json.tmp"))
            store.change(record.id, 1, "修改正文", 0, 0, 2)
            assertFailsWith<TutorialProblem> { store.flush() }
            store.deleteTutorial(record.id); store.flush()
            assertTrue(store.isDeleted(record.id)); assertFalse(Files.exists(root.resolve(record.id)))
        }
    }
}
