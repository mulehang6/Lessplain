package com.master.understanding.ide

import com.master.understanding.model.SourceAnchor
import com.master.understanding.storage.sha256
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceNavigatorTest {
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
}
