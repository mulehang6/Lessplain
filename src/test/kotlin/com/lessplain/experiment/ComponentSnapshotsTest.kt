package com.lessplain.experiment

import com.lessplain.experiment.core.ViewEngine
import com.lessplain.experiment.core.ViewValidation
import com.lessplain.experiment.core.componentLocation
import com.lessplain.experiment.core.componentSnapshotCurrent
import com.lessplain.experiment.ide.selection
import com.lessplain.experiment.model.*
import com.lessplain.experiment.storage.ViewStore
import com.lessplain.model.SourceSelectionRange
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ComponentSnapshotsTest {
    @TempDir lateinit var directory: Path
    private val validation by lazy { ViewValidation(requireNotNull(javaClass.getResourceAsStream("/webview/catalog.json")).bufferedReader().use { it.readText() }) }
    private fun document(format: String = "json", label: String = "页面结果") = ViewDocument("查看结果", JsonPayload(Json.parseToJsonElement(
        """{"root":"data","elements":{"data":{"type":"JsonView","props":{"label":"$label","format":"$format","value":{"${'$'}state":"/result"}}}}}""").jsonObject))
    private fun engine(store: ViewStore) = ViewEngine(store, { validation.catalog }, validation::validate) { emptyMap() }

    @Test fun freezesDataAndStateAndPreservesSavedQuestionsAfterRestart() {
        val path = directory.resolve("snapshot.db")
        lateinit var note: ViewNote
        ViewStore(path).use { store ->
            val engine = engine(store)
            val ref = engine.present(engine.context(null, ViewOrigin()).context, document()).ref
            val state = JsonPayload(buildJsonObject { put("result", "first") })
            val first = engine.componentLocation(ref, 1, "data", buildJsonObject { put("name", "first😀") }, state)
            assertEquals(first.target.id, engine.componentLocation(ref, 1, "data", buildJsonObject { put("name", "first😀") }, state).target.id)
            val origin = first.selection(0, first.sample.code.length)
            val second = engine.componentLocation(ref, 1, "data", JsonPrimitive("second"), JsonPayload.empty())
            assertNotEquals(first.sample.code, second.sample.code)
            assertEquals("json", first.sample.language)
            assertTrue(first.sample.code.contains("first😀"))
            note = engine.createNote("这个字段是什么意思", "question", origin)
            assertEquals(state, note.origin.state)
            assertEquals(origin, engine.context(engine.sourceRef(origin), ViewOrigin()).origin)
            engine.present(engine.context(ref, ViewOrigin()).context, document(label = "改版"))
            assertFails { engine.createNote("旧来源", "question", origin) }
            assertEquals(first.sample.code, engine.noteDetails(note.ref).origin.componentSnapshot!!.snapshot.content.code)
        }
        ViewStore(path).use { store ->
            assertEquals(note.origin, engine(store).noteDetails(note.ref).origin)
        }
    }

    @Test fun rejectsForgedSelectionWrongRevisionReleasedSnapshotAndSplitSurrogates() {
        ViewStore(directory.resolve("invalid.db")).use { store ->
            val engine = engine(store)
            val ref = engine.present(engine.context(null, ViewOrigin()).context, document()).ref
            assertFails { engine.componentLocation(ref, 2, "data", JsonNull, JsonPayload.empty()) }
            assertFails { engine.componentLocation(ref, 1, "missing", JsonNull, JsonPayload.empty()) }
            val location = engine.componentLocation(ref, 1, "data", JsonPrimitive("😀"), JsonPayload.empty())
            val origin = location.selection(0, location.sample.code.length)
            val selection = origin.componentSnapshot!!
            assertFails { engine.createNote("伪造", "question", origin.copy(componentSnapshot = selection.copy(snapshot = selection.snapshot.copy(content = CodeSample("坏", "txt", "forged"))))) }
            assertFails { engine.createNote("伪装", "question", origin.copy(entry = ViewOriginEntry.COMPONENT)) }
            val emoji = location.sample.code.indexOf("😀")
            assertFails { engine.createNote("截断", "question", origin.copy(componentSnapshot = selection.copy(selectionRange = SourceSelectionRange(emoji + 1, emoji + 2)))) }
            engine.releaseComponentSnapshot(location.target.id)
            assertFails { engine.createNote("已关闭", "question", origin) }
            assertTrue(engine.snapshot().notes.isEmpty())
        }
    }

    @Test fun rawTextIsExactAndUnrelatedStringsAreNeverParsed() {
        ViewStore(directory.resolve("raw.db")).use { store ->
            val engine = engine(store)
            val raw = "{\"path\":\"C:\\\\data\"}\r\n"
            val ref = engine.present(engine.context(null, ViewOrigin()).context, document("text")).ref
            val location = engine.componentLocation(ref, 1, "data", JsonPrimitive(raw), JsonPayload.empty())
            assertEquals(raw.replace("\r\n", "\n"), location.sample.code)
            assertEquals(raw, location.componentSnapshot!!.originalText)
            assertEquals("txt", location.sample.language)
            val origin = location.selection(0, location.sample.code.length)
            engine.delete(ref)
            assertFails { engine.createNote("已删除", "question", origin) }
        }
    }

    @Test fun sourceExcerptAlwaysUsesServerSideSavedCode() {
        val spec = JsonPayload(Json.parseToJsonElement("""{"root":"code","elements":{"code":{"type":"CodeExcerpt","props":{"source":"source"}}}}""").jsonObject)
        val source = ResolvedSource("Example.kt", "fun saved() = 1", version = "v1", line = 1)
        val view = ViewRecord("view:source", 1, ViewDocument("源码", spec), mapOf("source" to source), 1, 1)
        val location = componentLocation(view, "code", JsonPrimitive("forged code"), JsonPayload.empty())
        assertEquals(source.text, location.sample.code)
        assertEquals("kt", location.sample.language)
        assertTrue(componentSnapshotCurrent(view, location.componentSnapshot!!))
        assertFalse(componentSnapshotCurrent(view.copy(sources = mapOf("source" to source.copy(version = "v2"))), location.componentSnapshot!!))
    }

    @Test fun semanticToneUsesTheSameCatalogValidationOnTheBackend() {
        fun badge(tone: String) = ViewDocument("语义色", JsonPayload(Json.parseToJsonElement(
            """{"root":"badge","elements":{"badge":{"type":"Badge","props":{"text":"待核对","variant":"outline","tone":"$tone"}}}}""").jsonObject))
        validation.validate(badge("warning"))
        assertFails { validation.validate(badge("random")) }
    }
}
