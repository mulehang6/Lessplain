package com.lessplain.ide

import com.lessplain.model.SourceAnchor
import com.lessplain.storage.sha256
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceNavigatorTest {
    @Test
    fun `missing block returns unique function with changed evidence without changing old anchor`() {
        val content = "fun submit() { checkPermission(); save() }"
        val anchor = SourceAnchor("src/A.kt", "submit", "if (valid) save()", "old")
        val located = matchAnchorText(anchor, content, { error("旧文本不存在，不应按偏移查符号") }) {
            assertEquals("submit", it)
            listOf(0)
        }
        assertEquals(LocationStatus.RELOCATED, located.location)
        assertEquals(EvidenceStatus.CHANGED, located.evidence)
        assertEquals(0, located.offset)
        assertEquals("if (valid) save()", anchor.text)
    }

    @Test
    fun `missing symbol or multiple overloads never pick a function`() {
        val content = "fun submit() {}\nfun submit(value: Int) {}"
        val anchor = SourceAnchor("src/A.kt", "submit", "old", "old")
        val ambiguous = matchAnchorText(anchor, content, { null }) { listOf(0, 16) }
        assertEquals(LocationStatus.AMBIGUOUS, ambiguous.location)
        assertNull(ambiguous.offset)
        val unresolved = matchAnchorText(anchor.copy(symbol = null), content, { null }) { error("没有符号不能回退") }
        assertEquals(LocationStatus.UNRESOLVED, unresolved.location)
        assertNull(unresolved.offset)
    }

    @Test
    fun `wrong symbol and repeated text cannot fall back to function even with valid hint`() {
        val content = "fun submit() { save() }"
        val anchor = SourceAnchor("src/A.kt", "submit", "save()", sha256(content), 1)
        val wrong = matchAnchorText(anchor, content, { "other" }) { error("文本仍存在不能回退") }
        assertEquals(LocationStatus.UNRESOLVED, wrong.location)
        val repeated = content + "\n" + content
        val ambiguous = matchAnchorText(anchor.copy(version = "old"), repeated, { "submit" }) { error("文本歧义不能回退") }
        assertEquals(LocationStatus.AMBIGUOUS, ambiguous.location)
    }

    @Test
    fun `unchanged source cannot distinguish repeated text on the same hinted line`() {
        for (separator in listOf("\n", "\r\n")) {
            val content = "fun consume() {$separator    consume(item, item)$separator}"
            val anchor = SourceAnchor("src/A.kt", "consume", "item", sha256(content), 2)
            val located = matchAnchorText(anchor, content) { "consume" }
            assertEquals(LocationStatus.AMBIGUOUS, located.location)
            assertNull(located.offset)
        }
    }

    @Test
    fun `unchanged multiline source rejects two excerpts starting on the same hinted line`() {
        val content = "fun consume() { consume(); consume()\n}"
        val anchor = SourceAnchor("src/A.kt", "consume", "consume()", sha256(content), 1)
        assertEquals(LocationStatus.AMBIGUOUS, matchAnchorText(anchor, content) { "consume" }.location)
    }

    @Test
    fun `unchanged source still uses a unique match on the hinted line among other lines`() {
        val content = "fun consume() {\n    consume(item)\n    consume(item)\n}"
        val anchor = SourceAnchor("src/A.kt", "consume", "consume(item)", sha256(content), 3)
        val located = matchAnchorText(anchor, content) { "consume" }
        assertEquals(LocationStatus.EXACT, located.location)
        assertEquals(EvidenceStatus.UNCHANGED, located.evidence)
        assertEquals(content.lastIndexOf(anchor.text), located.offset)
    }

    @Test
    fun `outdated hash resolves an indented declaration by its code token and retains the full excerpt`() {
        for (separator in listOf("\n", "\r\n")) {
            val excerpt = "    private fun deliver() {$separator        send()$separator    }"
            val content = "class Service {$separator$excerpt$separator}"
            val anchor = SourceAnchor("src/Service.kt", "Service.deliver", excerpt, "0".repeat(64), 2)
            val offset = content.indexOf(excerpt)
            val lookedUp = mutableListOf<Int>()
            val located = matchAnchorText(anchor, content) { at ->
                lookedUp += at
                if (at == offset + 4) "Service.deliver" else "Service"
            }
            assertEquals(listOf(offset + 4), lookedUp)
            assertEquals(LocationStatus.RELOCATED, located.location)
            assertEquals(EvidenceStatus.UNKNOWN, located.evidence)
            assertEquals(offset, located.offset)
            assertEquals(excerpt, content.substring(located.offset!!, located.offset + excerpt.length))
            assertEquals(LocationStatus.UNRESOLVED, matchAnchorText(anchor, content) { "Other.deliver" }.location)
        }
    }

    @Test
    fun `leading blank lines do not prevent symbol validation or bypass duplicate detection`() {
        val excerpt = "\n\t  fun deliver() { send() }"
        val content = "class Service {$excerpt\n}"
        val anchor = SourceAnchor("src/Service.kt", "Service.deliver", excerpt, "0".repeat(64))
        val located = matchAnchorText(anchor, content) { at ->
            assertEquals(content.indexOf("fun deliver"), at)
            "Service.deliver"
        }
        assertEquals(content.indexOf(excerpt), located.offset)
        val duplicate = matchAnchorText(anchor, "$content\n$content") { error("Ambiguous text must not resolve a symbol") }
        assertEquals(LocationStatus.AMBIGUOUS, duplicate.location)
        assertNull(duplicate.offset)
    }
}
