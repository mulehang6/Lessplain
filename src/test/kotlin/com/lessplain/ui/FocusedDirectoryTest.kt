package com.lessplain.ui

import org.junit.jupiter.api.Test
import kotlin.test.*

class FocusedDirectoryTest {
    @Test
    fun `focus changes expansion while preserving real sibling order and avoiding unrelated traversal`() {
        val read = mutableListOf<String>()
        val dirs = mapOf(
            "/project" to listOf(DirectoryEntry("/project/shared", "shared", true), DirectoryEntry("/project/chat", "chat", true)),
            "/project/chat" to listOf(DirectoryEntry("/project/chat/state", "state", true), DirectoryEntry("/project/chat/export", "export", true)),
            "/project/chat/export" to listOf(DirectoryEntry("/project/chat/export/A.kt", "A.kt", false)),
            "/project/chat/state" to listOf(DirectoryEntry("/project/chat/state/B.kt", "B.kt", false)),
        )
        fun snapshot(path: String) = focusedDirectory(DirectoryEntry("/project", "project", true), path) { read += it; dirs[it].orEmpty() }
        val first = snapshot("/project/chat/export/A.kt")
        assertFalse("/project/shared" in read)
        val chat = first.children.first()
        assertEquals(listOf("export", "state"), chat.children.map { it.entry.name })
        assertTrue(chat.children.last().children.isEmpty())
        val second = snapshot("/project/chat/state/B.kt").children.first()
        assertEquals(chat.children.map { it.entry.name }, second.children.map { it.entry.name })
        assertTrue(second.children.first().children.isEmpty())
        assertEquals("B.kt", second.children.last().children.single().entry.name)
    }

    @Test
    fun `return restores individual viewports and changing route drops forward history`() {
        val history = DirectoryHistory("export")
        history.remember(DirectoryViewport(12, 80))
        history.visit("state")
        history.remember(DirectoryViewport(0, 200))
        history.visit("shared")
        history.travel(-2)
        assertEquals("export", history.current)
        assertEquals(DirectoryViewport(12, 80), history.viewport())
        history.travel(1)
        assertEquals(DirectoryViewport(0, 200), history.viewport())
        history.visit("other")
        assertFalse(history.canForward)
        history.visit("other")
        history.travel(-1)
        assertEquals("state", history.current)
    }
}
