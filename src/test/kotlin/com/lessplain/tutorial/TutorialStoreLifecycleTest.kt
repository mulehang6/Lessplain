package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TutorialStoreLifecycleTest {
    @TempDir lateinit var root: Path

    @Test fun `second instance is read only and closed writes never mutate the in memory snapshot`() {
        val first = TutorialStore(root)
        first.saveContext(TutorialContext("ctx", "问题", emptyList()))
        val record = first.create("ctx", "标题", "正文", TutorialResourceState())
        first.flush()
        TutorialStore(root).use { second ->
            assertTrue(second.readOnly)
            assertEquals(record, second.record(record.id))
            assertEquals("READ_ONLY", assertFailsWith<TutorialProblem> {
                second.change(record.id, 1, "丢失的修改", 0, 2, 5)
            }.code)
            assertEquals(record, second.record(record.id))
        }
        first.close()
        assertEquals("CLOSED", assertFailsWith<TutorialProblem> { first.saveReading(record.id, TutorialReadingState()) }.code)
        assertEquals(record, first.record(record.id))
        TutorialStore(root).use { assertFalse(it.readOnly) }
    }

    @Test fun `normalization makes CRLF create retries idempotent and all resources survive reopening`() {
        lateinit var id: String
        val graph = TutorialGraph(listOf(TutorialNode("n", "节点")), emptyList())
        val simulation = TutorialSimulation("s", "kotlin", "println(1)", listOf(SimulationScenario("a", "执行", listOf(SimulationStep(listOf(1), "输出", "1\n")))))
        val state = TutorialResourceState(listOf(TutorialDiagram("d", graph)), listOf(simulation))
        val markdown = "正文\r\n\r\n![图](lessplain://diagram/d)\r\n\r\n![模拟](lessplain://simulation/s)"
        val reading = TutorialReadingState()
        TutorialStore(root).use { store ->
            store.saveContext(TutorialContext("ctx", "问题", emptyList()))
            val record = store.create("ctx", "标题", markdown, state)
            id = record.id
            assertEquals(record, store.create("ctx", "标题", markdown, state))
            assertEquals(record, store.create("ctx", "标题", markdown.replace("\r\n", "\n"), state))
            store.saveReading(id, reading)
            store.savePositions(id, "d", mapOf("n" to TutorialPosition(15, 25)))
        }
        TutorialStore(root).use { store ->
            assertEquals(1, store.list().size)
            assertEquals(reading, store.reading(id))
            assertEquals("println(1)", store.resource(id).simulations.single().code)
            assertEquals(TutorialPosition(15, 25), store.resource(id).positions["d"]?.get("n"))
        }
    }

    @Test fun `stale answer receipt cannot resurrect a deleted question after interrupted multi file persistence`() {
        TutorialStore(root).use { store ->
            store.saveContext(TutorialContext("ctx", "问题", emptyList()))
            store.create("ctx", "标题", "正文", TutorialResourceState())
        }
        Files.writeString(root.resolve("tool-receipts.json"), """[{"key":"stale","reply":"{\"ok\":true,\"data\":{\"id\":\"q_deleted\"}}"}]""")
        TutorialStore(root).use { assertNull(it.toolReceipt("stale")) }
    }
}
