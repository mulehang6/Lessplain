package com.lessplain.experiment

import com.lessplain.experiment.core.ViewEngine
import com.lessplain.experiment.core.ViewValidation
import com.lessplain.experiment.ide.selection
import com.lessplain.experiment.model.*
import com.lessplain.experiment.storage.ViewStore
import com.lessplain.model.SourceSelectionRange
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class CodeSamplesTest {
    @TempDir lateinit var directory: Path
    private val validation = ViewValidation(requireNotNull(javaClass.getResourceAsStream("/webview/catalog.json")).bufferedReader().use { it.readText() })
    private fun document(code: String = "fun main() {\n    println(\"😀\")\n}") = ViewDocument("等待如何工作？",
        JsonPayload(Json.parseToJsonElement("""{"root":"g","elements":{"g":{"type":"Graph","props":{"nodes":[{"id":"wait","label":"等待","sample":{"id":"wait","startLine":2}}],"edges":[]}}}}""").jsonObject),
        codeSamples = mapOf("wait" to CodeSample("等待.kt", "kotlin", code)))
    private fun engine(store: ViewStore) = ViewEngine(store, { validation.catalog }, validation::validate) {
        assertTrue(it.isEmpty(), "教学代码不能被当作项目路径解析")
        emptyMap()
    }

    @Test fun freezesSelectionAndKeepsItAfterRevisionAndRestart() {
        val database = directory.resolve("samples.db")
        lateinit var question: ViewNote
        lateinit var original: CodeSample
        ViewStore(database).use { store ->
            val engine = engine(store)
            val saved = engine.present(engine.context(null, ViewOrigin()).context, document())
            val location = engine.sampleLocation(saved.ref, 1, CodeSampleTarget("wait", 2), "g", "wait", JsonPayload.empty())
            original = location.sample
            val origin = location.selection(0, original.code.length)
            question = engine.createNote("这一行为什么等待？", "question", origin)
            val sourceRef = engine.sourceRef(origin)
            assertEquals(origin, engine.context(sourceRef, ViewOrigin()).origin)
            assertEquals(origin, engine.context(question.ref, ViewOrigin()).origin)
            engine.present(engine.context(saved.ref, ViewOrigin()).context, document("fun changed() {}".replace("{}", "{\n}")))
            assertFailsWith<IllegalStateException> { engine.createNote("不能使用旧选区", "question", origin) }
            assertEquals(original, engine.context(question.ref, ViewOrigin()).origin.sample?.sample)
            assertFailsWith<IllegalStateException> { engine.sampleLocation(saved.ref, 1, CodeSampleTarget("wait", 2), "g", "wait", JsonPayload.empty()) }
        }
        ViewStore(database).use { store ->
            val engine = engine(store)
            assertEquals(original, engine.noteDetails(question.ref).origin.sample?.sample)
            val context = engine.context(question.ref, ViewOrigin())
            val answer = engine.present(context.context, document().copy(question = question.text))
            assertEquals(original, engine.snapshot().views.single { it.ref == answer.ref }.answeredNote?.origin?.sample?.sample)
            engine.delete(context.target.parentRef!!)
            assertFailsWith<IllegalStateException> { engine.context(question.ref, ViewOrigin()) }
            assertFailsWith<IllegalStateException> { engine.present(context.context, document().copy(question = question.text)) }
        }
    }

    @Test fun rejectsForgedReferencesRangesAndEntryKinds() {
        ViewStore(directory.resolve("ranges.db")).use { store ->
            val engine = engine(store)
            val saved = engine.present(engine.context(null, ViewOrigin()).context, document())
            val target = CodeSampleTarget("wait", 2)
            assertFailsWith<IllegalStateException> { engine.sampleLocation(saved.ref, 1, target.copy(id = "missing"), "g", "wait", JsonPayload.empty()) }
            assertFailsWith<IllegalStateException> { engine.sampleLocation(saved.ref, 1, target.copy(startLine = 1), "g", "wait", JsonPayload.empty()) }
            assertFailsWith<IllegalArgumentException> { engine.sampleLocation(saved.ref, 1, target.copy(endLine = 90), null, null, JsonPayload.empty()) }
            val location = engine.sampleLocation(saved.ref, 1, target, "g", "wait", JsonPayload.empty())
            val origin = location.selection(0, location.sample.code.length)
            assertFailsWith<IllegalArgumentException> { engine.createNote("伪装成节点", "question", origin.copy(entry = ViewOriginEntry.NODE)) }
            assertFailsWith<IllegalStateException> { engine.createNote("伪造正文", "question", origin.copy(sample = origin.sample!!.copy(sample = location.sample.copy(code = "forged")))) }
            val emoji = location.sample.code.indexOf("😀")
            assertFailsWith<IllegalArgumentException> { engine.createNote("截断字符", "question", origin.copy(sample = origin.sample!!.copy(selectionRange = SourceSelectionRange(emoji + 1, emoji + 2)))) }
            assertTrue(engine.snapshot().notes.isEmpty())
        }
    }

    @Test fun validatesActualTeachingPageAndRejectsMissingResourceOrBadLines() {
        val actual = java.nio.file.Files.readString(Path.of("docs/examples/coroutine-waiting.view.json"))
        val page = Json.decodeFromString<ViewDocument>(actual)
        validation.validate(page)
        assertEquals(2, page.codeSamples?.size)
        assertFails { validation.validate(page.copy(codeSamples = emptyMap())) }
        assertFails { validation.validate(document().copy(codeSamples = mapOf("wait" to CodeSample("空", "kotlin", "")))) }
        assertFails { validation.validate(document("one line")) }
        val legacy = Json.decodeFromString<ViewDocument>("""{"question":"旧页面","spec":{"root":"t","elements":{"t":{"type":"Text","props":{"text":"旧"}}}}}""")
        assertNull(legacy.codeSamples)
        validation.validate(legacy)
    }

    @Test fun normalizesLineEndingsBeforeCapturingNativeEditorOffsets() {
        ViewStore(directory.resolve("lines.db")).use { store ->
            val engine = engine(store)
            val saved = engine.present(engine.context(null, ViewOrigin()).context, document("fun main() {\r\n    println(1)\r\n}"))
            val location = engine.sampleLocation(saved.ref, 1, CodeSampleTarget("wait", 2), "g", "wait", JsonPayload.empty())
            assertFalse(location.sample.code.contains('\r'))
            engine.createNote("第二行", "question", location.selection(13, location.sample.code.length - 1))
        }
    }

    @Test fun legacyReceiptCanStillRetryWithoutTeachingResources() {
        ViewStore(directory.resolve("legacy.db")).use { store ->
            val engine = engine(store)
            val legacy = Json.decodeFromString<ViewDocument>("""{"question":"旧问题","spec":{"root":"t","elements":{"t":{"type":"Text","props":{"text":"旧"}}}}}""")
            val context = engine.context(null, ViewOrigin())
            val receipt = engine.present(context.context, legacy)
            // 旧版 ViewStore 开启 encodeDefaults，未命名页面仍序列化 title:null。
            val oldPayload = """{"demos":{},"question":"旧问题","sources":{},"spec":{"elements":{"t":{"props":{"text":"旧"},"type":"Text"}},"root":"t"},"title":null}"""
            val oldHash = java.security.MessageDigest.getInstance("SHA-256").digest(oldPayload.toByteArray()).joinToString("") { "%02x".format(it) }
            store.transaction {
                val commit = Json.parseToJsonElement(get("commits", context.context)!!).jsonObject
                put("commits", context.context, JsonObject(commit + ("hash" to JsonPrimitive(oldHash))).toString())
            }
            assertEquals(receipt, engine.present(context.context, legacy))
        }
    }
}
