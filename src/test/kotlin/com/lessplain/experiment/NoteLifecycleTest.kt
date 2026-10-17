package com.lessplain.experiment

import com.lessplain.experiment.core.ViewEngine
import com.lessplain.experiment.model.*
import com.lessplain.experiment.storage.ViewStore
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

/** 硬删除与答后归档必须同时覆盖行、凭据、冻结来源和历史重建。 */
class NoteLifecycleTest {
    @TempDir lateinit var directory: Path
    private fun engine(store: ViewStore) = ViewEngine(store, { JsonPayload.empty() }, {}) { emptyMap() }
    private fun document(question: String = "入口") = ViewDocument(
        question, JsonPayload(Json.parseToJsonElement("""{"root":"t","elements":{"t":{"type":"Text","props":{"text":"说明"}}}}""").jsonObject),
    )

    @Test fun deleteRemovesEveryUncommittedVersionAndRejectsOldReferences() {
        ViewStore(directory.resolve("delete.db")).use { store ->
            val engine = engine(store)
            val original = engine.createNote("原问题", "question", ViewOrigin())
            val old = engine.context(original.ref, ViewOrigin())
            val edited = engine.editNote(original.ref, "修改的问题")
            val fresh = engine.context(edited.ref, ViewOrigin())
            assertFailsWith<IllegalStateException> { engine.deleteNote(original.ref) }
            engine.deleteNote(edited.ref)
            assertTrue(engine.snapshot().notes.isEmpty())
            assertTrue(store.read { all("notes").isEmpty() && all("contexts").isEmpty() })
            for (ref in listOf(original.ref, edited.ref)) {
                assertFailsWith<IllegalStateException> { engine.context(ref, ViewOrigin()) }
                assertFailsWith<IllegalStateException> { engine.editNote(ref, "不能复活") }
            }
            for (ticket in listOf(old, fresh)) assertFailsWith<IllegalStateException> { engine.present(ticket.context, document(edited.text)) }
        }
    }

    @Test fun answerAtomicallyDeletesRowAndReconstructsVersionedHistoryAfterRestart() {
        val path = directory.resolve("answer.db")
        lateinit var note: ViewNote
        lateinit var receipt: PresentReceipt
        lateinit var context: ContextResult
        ViewStore(path).use { store ->
            val engine = engine(store)
            val root = engine.present(engine.context(null, ViewOrigin()).context, document())
            note = engine.createNote("为什么命中？", "question", ViewOrigin(viewRef = root.ref, nodeId = "cache", state = JsonPayload(buildJsonObject { put("frozen", 7) })))
            context = engine.context(note.ref, ViewOrigin())
            val losing = engine.context(note.ref, ViewOrigin())
            receipt = engine.present(context.context, document(note.text))
            assertTrue(store.read { all("notes").isEmpty() })
            assertTrue(engine.snapshot().notes.isEmpty())
            val answer = engine.snapshot().views.single { it.ref == receipt.ref }
            assertEquals(note.ref, answer.answeredNote?.ref)
            assertEquals(note.origin, answer.answeredNote?.origin)
            assertEquals(root.ref, answer.parentRef)
            assertFailsWith<IllegalStateException> { engine.present(losing.context, document(note.text)) }
            assertFailsWith<IllegalArgumentException> { engine.deleteNote(note.ref) }
            assertEquals(receipt, engine.present(context.context, document(note.text)))
        }
        ViewStore(path).use { store ->
            val engine = engine(store)
            val reopened = engine.context(note.ref, ViewOrigin())
            assertEquals(receipt.ref, reopened.target.ref)
            assertEquals(note.origin, reopened.origin)
            assertEquals(receipt.ref, engine.noteDetails(note.ref).answerRef)
            val updated = engine.present(reopened.context, document(note.text))
            assertEquals(receipt.ref, updated.ref)
            assertEquals(2, updated.revision)
            assertEquals(note.ref, engine.snapshot().views.last().answeredNote?.ref)
            assertTrue(engine.snapshot().notes.isEmpty())
        }
    }

    @Test fun legacyRowsArchiveBeforeRemovalAndUpgradeIsIdempotent() {
        ViewStore(directory.resolve("upgrade.db")).use { store ->
            var engine = engine(store)
            val root = engine.present(engine.context(null, ViewOrigin()).context, document())
            val answered = engine.createNote("历史问题", "question", ViewOrigin(viewRef = root.ref))
            val obsolete = engine.context(answered.ref, ViewOrigin())
            val answer = engine.present(engine.context(answered.ref, ViewOrigin()).context, document(answered.text))
            val cancelled = engine.createNote("旧取消", "question", ViewOrigin())
            val invalid = engine.context(cancelled.ref, ViewOrigin())
            store.transaction {
                val record = store.json.decodeFromString<ViewRecord>(get("views", answer.ref)!!)
                put("views", answer.ref, store.json.encodeToString(record.copy(answeredNote = null)))
                put("notes", answered.ref.substringBefore('@'), store.json.encodeToString(answered.copy(status = "answered", answerRef = answer.ref)))
                put("notes", cancelled.ref.substringBefore('@'), store.json.encodeToString(cancelled.copy(status = "cancelled")))
                put("contexts", obsolete.context, store.json.encodeToString(ContextTicket(obsolete.context, answered.origin, noteRef = answered.ref, noteRevision = 1)))
            }
            repeat(2) { engine = engine(store) }
            assertTrue(store.read { all("notes").isEmpty() })
            assertEquals(answer.ref, engine.context(answered.ref, ViewOrigin()).target.ref)
            assertEquals(2, engine.snapshot().views.size)
            assertNull(store.read { get("contexts", invalid.context) })
            assertNull(store.read { get("contexts", obsolete.context) })
            assertFailsWith<IllegalStateException> { engine.context(cancelled.ref, ViewOrigin()) }
        }
    }

    @Test fun deleteAndAnswerRaceHasOnlyOneWinnerWithoutPartialHistory() {
        repeat(8) { index ->
            ViewStore(directory.resolve("race-$index.db")).use { store ->
                val engine = engine(store)
                val note = engine.createNote("并发问题", "question", ViewOrigin())
                val context = engine.context(note.ref, ViewOrigin())
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val answer = pool.submit<Boolean> { start.await(); runCatching { engine.present(context.context, document(note.text)) }.isSuccess }
                    val delete = pool.submit<Boolean> { start.await(); runCatching { engine.deleteNote(note.ref) }.isSuccess }
                    start.countDown()
                    val answered = answer.get()
                    assertNotEquals(answered, delete.get())
                    assertTrue(store.read { all("notes").isEmpty() })
                    assertEquals(if (answered) 1 else 0, engine.snapshot().views.size)
                    if (answered) assertEquals(note.ref, engine.snapshot().views.single().answeredNote?.ref)
                } finally { pool.shutdownNow() }
            }
        }
    }

    @Test fun orphanLegacyAnswerPreservesQuestionAsPendingInsteadOfBlockingHistory() {
        ViewStore(directory.resolve("orphan.db")).use { store ->
            val note = engine(store).createNote("回答已被单独删除", "question", ViewOrigin())
            store.transaction {
                put("notes", note.ref.substringBefore('@'), store.json.encodeToString(note.copy(status = "answered", answerRef = "view:missing")))
            }
            val upgraded = engine(store)
            assertEquals(note, upgraded.snapshot().notes.single())
            assertNull(upgraded.context(note.ref, ViewOrigin()).target.ref)
        }
    }

    @Test fun deletingTreeRevokesReceiptsAndSourcesButKeepsOtherTreesAndIndependentFrozenTasks() {
        val path = directory.resolve("subtree.db")
        lateinit var liveContext: ContextResult
        lateinit var liveReceipt: PresentReceipt
        lateinit var answeredContext: ContextResult
        lateinit var answeredReceipt: PresentReceipt
        ViewStore(path).use { store ->
            val engine = engine(store)
            val rootContext = engine.context(null, ViewOrigin())
            val root = engine.present(rootContext.context, document())
            val question = engine.createNote("子问题", "question", ViewOrigin(viewRef = root.ref))
            val childContext = engine.context(question.ref, ViewOrigin())
            val child = engine.present(childContext.context, document(question.text))
            val update = engine.context(child.ref, ViewOrigin())
            val pending = engine.createNote("未答", "question", ViewOrigin(viewRef = child.ref))
            val pendingContext = engine.context(pending.ref, ViewOrigin())
            val sourceRef = engine.sourceRef(ViewOrigin(viewRef = child.ref))
            val frozen = engine.context(null, ViewOrigin(viewRef = root.ref))
            liveContext = engine.context(null, ViewOrigin())
            liveReceipt = engine.present(liveContext.context, document("存活"))
            val other = engine.createNote("保留回答", "question", ViewOrigin(viewRef = liveReceipt.ref))
            answeredContext = engine.context(other.ref, ViewOrigin())
            answeredReceipt = engine.present(answeredContext.context, document(other.text))
            // 回答归档会移除原 ticket，合法收据仍必须可重试。
            assertNull(store.read { get("contexts", answeredContext.context) })
            val liveUpdate = engine.context(liveReceipt.ref, ViewOrigin())
            engine.saveBrowse(root.ref, JsonPayload.empty())
            engine.delete(root.ref)
            for ((context, text) in listOf(rootContext to "入口", childContext to question.text, update to question.text, pendingContext to pending.text)) {
                assertNull(store.read { get("contexts", context.context) })
                assertNull(store.read { get("commits", context.context) })
                assertFailsWith<IllegalStateException> { engine.present(context.context, document(text)) }
            }
            assertNull(store.read { get("browse", root.ref) })
            assertNull(store.read { get("origins", sourceRef) })
            assertFailsWith<IllegalStateException> { engine.context(sourceRef, ViewOrigin()) }
            assertFailsWith<IllegalStateException> { engine.context(question.ref, ViewOrigin()) }
            assertFailsWith<IllegalStateException> { engine.context(pending.ref, ViewOrigin()) }
            assertNotNull(store.read { get("contexts", liveUpdate.context) })
            assertEquals(liveReceipt, engine.present(liveContext.context, document("存活")))
            assertEquals(answeredReceipt, engine.present(answeredContext.context, document(other.text)))
            val independent = engine.present(frozen.context, document("独立任务"))
            assertEquals(root.ref, engine.snapshot().views.single { it.ref == independent.ref }.origin.viewRef)
            assertNull(engine.snapshot().views.single { it.ref == independent.ref }.parentRef)
        }
        ViewStore(path).use { store ->
            val engine = engine(store)
            assertEquals(liveReceipt, engine.present(liveContext.context, document("存活")))
            assertEquals(answeredReceipt, engine.present(answeredContext.context, document("保留回答")))
        }
    }

    @Test fun cleanupFailureRollsBackPageNotesBrowseAndReceiptsTogether() {
        ViewStore(directory.resolve("rollback.db")).use { store ->
            val engine = engine(store)
            val context = engine.context(null, ViewOrigin())
            val root = engine.present(context.context, document())
            val note = engine.createNote("保留问题", "question", ViewOrigin(viewRef = root.ref))
            engine.saveBrowse(root.ref, JsonPayload.empty())
            store.transaction { put("contexts", "context:corrupt", "{") }
            assertFails { engine.delete(root.ref) }
            assertEquals(root.ref, engine.snapshot().views.single().ref)
            assertEquals(note.ref, engine.snapshot().notes.single().ref)
            assertNotNull(store.read { get("browse", root.ref) })
            assertNotNull(store.read { get("commits", context.context) })
        }
    }
}
