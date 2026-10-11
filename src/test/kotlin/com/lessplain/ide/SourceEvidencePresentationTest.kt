package com.lessplain.ide

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceEvidencePresentationTest {
    @Test
    fun `recovered location still exposes changed evidence`() {
        assertEquals("仅定位到所属函数，原片段已变化",
            sourceEvidenceMessage(LocatedSource(LocationStatus.RELOCATED, EvidenceStatus.CHANGED, 3)))
        assertNull(sourceEvidenceMessage(LocatedSource(LocationStatus.RELOCATED, EvidenceStatus.UNKNOWN, 3)))
    }

    @Test
    fun `unrelated edits preserve excerpt but replacement or invalid range removes it`() {
        assertFalse(sourceExcerptChanged("prefix save() suffix", 7, 13, "save()"))
        assertTrue(sourceExcerptChanged("prefix load() suffix", 7, 13, "save()"))
        assertTrue(sourceExcerptChanged("short", 7, 13, "save()"))
    }
}
