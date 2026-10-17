package com.lessplain.experiment

import com.lessplain.experiment.core.ViewEngine
import com.lessplain.experiment.model.*
import com.lessplain.experiment.storage.ViewStore
import com.lessplain.model.SourceSelectionRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** 精确选区随待答、上下文及回答快照持久化，旧记录仍可读取。 */
class SelectionSnapshotTest {
    @TempDir lateinit var directory: Path

    @Test fun selectionRangeSurvivesAnswerAndReopen() {
        val source = ResolvedSource("Example.kt", "item", version = "version", line = 1,
            selectionRange = SourceSelectionRange(8, 12))
        val file = directory.resolve("views.db")
        lateinit var answerRef: String
        ViewStore(file).use { store ->
            val engine = ViewEngine(store, { JsonPayload.empty() }, {}, { it }) { emptyMap() }
            val note = engine.createNote("为什么选这个？", "question", ViewOrigin(source = source, entry = ViewOriginEntry.SOURCE_SELECTION))
            assertEquals(source, engine.noteDetails(note.ref).origin.source)
            val context = engine.context(note.ref, ViewOrigin())
            assertEquals(source, context.origin.source)
            val document = ViewDocument(note.text, JsonPayload(Json.parseToJsonElement(
                """{"root":"text","elements":{"text":{"type":"Text","props":{"text":"解释"}}}}""",
            ).jsonObject))
            answerRef = engine.present(context.context, document).ref
            assertTrue(engine.snapshot().notes.isEmpty())
        }
        ViewStore(file).use { store ->
            val engine = ViewEngine(store, { JsonPayload.empty() }, {}) { emptyMap() }
            val answer = engine.snapshot().views.single { it.ref == answerRef }
            assertEquals(source, answer.origin.source)
            assertEquals(source, answer.answeredNote?.origin?.source)
            assertNull(store.json.decodeFromString<ResolvedSource>(
                """{"path":"Old.kt","text":"item","version":"old","line":1}""",
            ).selectionRange)
        }
    }
}
