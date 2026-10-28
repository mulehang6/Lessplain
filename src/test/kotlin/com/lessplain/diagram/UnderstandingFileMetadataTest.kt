package com.lessplain.diagram

import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingRecord
import org.junit.jupiter.api.Test
import kotlin.test.*

class UnderstandingFileMetadataTest {
    @Test fun `compact editor tab preserves a full readable platform tooltip without overriding the URL`() {
        val question = "完整问题中文😀".repeat(10) + " / 路径\\名称\n下一行"
        val record = UnderstandingRecord("r", 1, null, null, question, "", null, UnderstandingGraph(emptyList(), emptyList()), 0)
        val file = UnderstandingVirtualFile(record)
        assertEquals(understandingTabTitle(record, null), file.tabTitle)
        assertTrue(file.tabTitle.codePointCount(0, file.tabTitle.length) <= 13)
        assertTrue(file.name.length > file.tabTitle.length)
        assertFalse(file.name.any { it == '/' || it == '\\' || it == '\n' })
        assertTrue(file.presentableUrl.contains(file.name))
        assertEquals(question, file.fullTitle)
        val renamed = record.copy(revision = 2, displayTitle = "新的完整标题".repeat(5))
        file.update(renamed)
        assertEquals(renamed.displayTitle, file.name)
        assertEquals(understandingTabTitle(renamed, null), file.tabTitle)
    }
}
