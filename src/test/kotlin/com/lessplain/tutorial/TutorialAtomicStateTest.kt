package com.lessplain.tutorial

import com.google.gson.Gson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TutorialAtomicStateTest {
    @TempDir lateinit var root: Path

    @Test fun `failed annotation deletion retains the stored page and success survives reopening`() {
        lateinit var record: TutorialRecord
        TutorialStore(root).use { store ->
            record = create(store)
            val note = TutorialAnnotation("note_delete", record.id, "note", 0, 2, "正文", text = "个人笔记")
            store.createAnnotation(note); store.flush()
            val blocked = root.resolve(record.id).resolve("content.json.tmp")
            Files.createDirectory(blocked)
            assertEquals("PERSISTENCE_FAILED", assertFailsWith<TutorialProblem> { store.deleteAnnotation(note.id) }.code)
            assertEquals(note, store.annotation(note.id), "写失败不能先把当前窗口中的批注删掉")
            TutorialStore(root).use { reader -> assertEquals(note, reader.annotation(note.id)) }
            Files.delete(blocked)
            assertEquals(listOf(note), store.deleteAnnotation(note.id))
            store.flush()
        }
        TutorialStore(root).use { assertTrue(it.annotations(record.id).isEmpty()) }
    }

    @Test fun `resources and their source index recover as one snapshot`() {
        lateinit var record: TutorialRecord
        val first = TutorialDiagram("delivery", TutorialGraph(listOf(TutorialNode("n", "甲", "src_a")), emptyList()))
        val next = first.copy(graph = TutorialGraph(listOf(TutorialNode("n", "乙", "src_b")), emptyList()))
        TutorialStore(root).use { store ->
            store.saveContext(TutorialContext("ctx", "来源", emptyList()))
            listOf("a", "b").forEach { store.saveSource(TutorialSource("src_$it", "$it.kt", it, it, 0, 1, "hash")) }
            record = store.create("ctx", "插图", "![图](lessplain://diagram/delivery)", TutorialResourceState(diagrams = listOf(first)))
            store.flush()
            val blocked = root.resolve(record.id).resolve("content.json.tmp"); Files.createDirectory(blocked)
            store.saveDiagram(record.id, next, 1)
            assertFailsWith<TutorialProblem> { store.flush() }
            TutorialStore(root).use { reader ->
                assertEquals(first.copy(revision = 1), reader.resource(record.id).diagrams.single())
                assertEquals(listOf("src_a"), reader.record(record.id).sourceIds)
            }
            // 失败持续到关闭；重开仍读取上次完整提交，不能读到半份新资源。
        }
        Files.delete(root.resolve(record.id).resolve("content.json.tmp"))
        TutorialStore(root).use { store -> store.saveDiagram(record.id, next, 1); store.flush() }
        TutorialStore(root).use { store ->
            assertEquals(next.graph, store.resource(record.id).diagrams.single().graph)
            assertEquals(listOf("src_b"), store.record(record.id).sourceIds)
        }
    }

    @Test fun `answer receipt remains with its answer even when the root receipt file fails`() {
        lateinit var record: TutorialRecord
        TutorialStore(root).use { store ->
            record = create(store)
            store.createAnnotation(TutorialAnnotation("q_answer", record.id, "question", 0, 2, "正文", text = "为什么", status = "pending"))
            store.registerToolWrite("answer_a", "q_answer")
            val first = store.updateAnnotation("q_answer", "answer_a") { it.copy(answer = "答复甲", status = "answered") }
            store.flush()
            Files.createDirectory(root.resolve("tool-receipts.json.tmp"))
            store.saveToolReceipt("answer_a", Gson().toJson(mapOf("ok" to true, "data" to first)))
            assertFailsWith<TutorialProblem> { store.flush() }
        }
        Files.delete(root.resolve("tool-receipts.json.tmp"))
        TutorialStore(root).use { store ->
            store.updateAnnotation("q_answer") { it.copy(answer = "答复乙") }; store.flush()
            val originalReply = assertNotNull(store.toolReceipt("answer_a"))
            assertTrue(originalReply.contains("答复甲"), "旧请求必须重放原回执")
            assertEquals("答复乙", store.annotation("q_answer").answer, "重放回执不应再次写入旧答案")
        }
    }

    @Test fun `reopening repairs receipt cleanup after a committed tutorial deletion`() {
        lateinit var record: TutorialRecord
        TutorialStore(root).use { store ->
            record = create(store)
            val reply = Gson().toJson(mapOf("ok" to true, "data" to mapOf("tutorial" to record)))
            store.saveToolReceipt("create", reply)
            // 模拟早期根目录回执留下的正文。
            Files.writeString(root.resolve("receipts.json"), Gson().toJson(listOf(mapOf("key" to "legacy", "record" to record))))
            store.flush()
            Files.createDirectory(root.resolve("receipts.json.tmp"))
            Files.createDirectory(root.resolve("tool-receipts.json.tmp"))
            assertEquals("CLEANUP_PENDING", assertFailsWith<TutorialProblem> { store.deleteTutorial(record.id) }.code)
        }
        Files.delete(root.resolve("receipts.json.tmp")); Files.delete(root.resolve("tool-receipts.json.tmp"))
        TutorialStore(root).use { it.flush(); assertTrue(it.isDeleted(record.id)) }
        assertFalse(Files.readString(root.resolve("receipts.json")).contains("正文"))
        assertFalse(Files.readString(root.resolve("tool-receipts.json")).contains("正文"))
    }

    @Test fun `annotation deletion keeps its context unavailable when metadata cleanup fails until reopen`() {
        lateinit var record: TutorialRecord
        TutorialStore(root).use { store ->
            record = create(store)
            store.saveContext(TutorialContext("ctx_deleted_note", "应删除的问题", emptyList(), materialId = record.id))
            store.createAnnotation(TutorialAnnotation("q_context", record.id, "question", 0, 2, "正文", text = "问题", contextId = "ctx_deleted_note"))
            store.flush()
            Files.createDirectory(root.resolve("contexts.json.tmp"))
            store.deleteAnnotation("q_context")
            assertFailsWith<TutorialProblem> { store.context("ctx_deleted_note") }
            assertFailsWith<TutorialProblem> { store.flush() }
        }
        Files.delete(root.resolve("contexts.json.tmp"))
        TutorialStore(root).use { store ->
            assertTrue(store.annotations(record.id).isEmpty())
            assertEquals("NOT_FOUND", assertFailsWith<TutorialProblem> { store.context("ctx_deleted_note") }.code)
            store.flush()
        }
        assertFalse(Files.readString(root.resolve("contexts.json")).contains("应删除的问题"))
        assertFalse(Files.readString(root.resolve(record.id).resolve("content.json")).contains("ctx_deleted_note"), "清理完成后回收上下文删除登记")
    }

    @Test fun `resource and original reply commit together before a later revision`() {
        lateinit var record: TutorialRecord
        val first = TutorialSimulation("play", "kotlin", "println(1)", listOf(SimulationScenario("a", "原情境", listOf(SimulationStep(listOf(1), "第一步", "1")))))
        TutorialStore(root).use { store ->
            record = create(store)
            store.registerToolWrite("simulation_a", record.id)
            store.saveSimulation(record.id, first, 0, "simulation_a")
            store.flush()
        }
        // 全局回执不存在，首次原子提交仍须保留原响应。
        TutorialStore(root).use { store ->
            store.saveSimulation(record.id, first.copy(code = "println(2)"), 1)
            store.flush()
            val reply = assertNotNull(store.toolReceipt("simulation_a"))
            assertTrue(reply.contains("println(1)"))
            assertEquals("println(2)", store.resource(record.id).simulations.single().code)
        }
    }

    private fun create(store: TutorialStore): TutorialRecord {
        store.saveContext(TutorialContext("ctx", "来源", emptyList()))
        return store.create("ctx", "标题", "正文", TutorialResourceState(), "create")
    }
}
