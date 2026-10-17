package com.lessplain.experiment

import com.lessplain.experiment.browser.*
import com.lessplain.experiment.ide.sourceNavigationRequest
import com.lessplain.experiment.model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class BrowserProtocolTest {
    @Test fun pageGenerationsAndCoalescing() {
        val sent = mutableListOf<String>()
        val outbox = BridgeOutbox(sent::add)
        val old = outbox.newPage()
        outbox.reply(old, "stale")
        val current = outbox.newPage()
        outbox.reply(old, "also stale")
        outbox.snapshot("""{"snapshot":1}""")
        outbox.snapshot("""{"snapshot":2}""")
        outbox.reply(current, "reply")
        outbox.ready(old)
        assertTrue(sent.isEmpty())
        outbox.ready(current)
        assertEquals(listOf("reply", """{"snapshot":2}"""), sent)
        outbox.close()
        outbox.reply(current, "closed")
        assertEquals(2, sent.size)
    }
    @Test fun themeDoesNotReplaceBufferedSnapshot() {
        val sent = mutableListOf<String>()
        val outbox = BridgeOutbox(sent::add)
        val page = outbox.newPage()
        outbox.snapshot("""{"snapshot":1}""")
        outbox.snapshot("""{"theme":"dark"}""")
        outbox.ready(page)
        assertEquals(listOf("""{"snapshot":1,"theme":"dark"}"""), sent)
    }
    @Test fun staticResourceBoundary() {
        assertEquals("webview/assets/elk-worker.js", resourcePath("https://lessplain.local/assets/elk-worker.js"))
        assertEquals("text/javascript", resourceMime("assets/elk-worker.js"))
        listOf("http://lessplain.local/index.html", "https://evil.local/index.html", "https://lessplain.local/../secret", "https://lessplain.local/%2e%2e/secret", "file:///secret").forEach { assertNull(resourcePath(it)) }
    }
    @Test fun latestNavigationWinsBeforePageIsReady() {
        val sent = mutableListOf<String>()
        val outbox = BridgeOutbox(sent::add)
        val page = outbox.newPage()
        outbox.snapshot("""{"showHistory":true,"snapshot":1}""")
        outbox.snapshot("""{"select":"view:new","snapshot":2}""")
        outbox.ready(page)
        assertFalse(sent.single().contains("showHistory"))
        assertTrue(sent.single().contains("view:new"))
        val next = outbox.newPage()
        sent.clear()
        outbox.snapshot("""{"select":"view:old","snapshot":1}""")
        outbox.snapshot("""{"showHistory":true,"snapshot":2}""")
        outbox.ready(next)
        assertFalse(sent.single().contains("select"))
        assertTrue(sent.single().contains("showHistory"))
    }

    @Test fun navigationRequestsKeepOrderAndAreDroppedWithTheirPage() {
        val sent = mutableListOf<String>()
        val outbox = BridgeOutbox(sent::add)
        val old = outbox.newPage()
        outbox.event(old, """{"navigationRequest":{"id":"old"}}""")
        val current = outbox.newPage()
        outbox.event(old, """{"navigationRequest":{"id":"stale"}}""")
        outbox.event(current, """{"navigationRequest":{"id":"one"}}""")
        outbox.event(current, """{"navigationRequest":{"id":"two"}}""")
        outbox.ready(current)
        assertEquals(
            listOf(
                """{"navigationRequest":{"id":"one"}}""",
                """{"navigationRequest":{"id":"two"}}""",
            ),
            sent,
        )
    }

    @Test fun cursorCommandAcceptsOnlySemanticBrowserCursors() {
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, BrowserCursor.parse("default").awtType)
        assertEquals(java.awt.Cursor.HAND_CURSOR, BrowserCursor.parse("pointer").awtType)
        assertEquals(java.awt.Cursor.TEXT_CURSOR, BrowserCursor.parse("text").awtType)
        assertEquals(java.awt.Cursor.HAND_CURSOR, BrowserCursor.parse("grab").awtType)
        assertEquals(java.awt.Cursor.MOVE_CURSOR, BrowserCursor.parse("grabbing").awtType)
        assertFailsWith<IllegalStateException> { BrowserCursor.parse("resize") }
    }

    @Test fun sourceStepEventCarriesTheFrozenCanvasIdentity() {
        val binding = EditorSourceBinding(
            viewRef = "view:fixed",
            revision = 7,
            elementId = "graph-main",
            nodeId = "node-cache",
            sourceId = "source-cache",
            label = "缓存节点",
            state = JsonPayload.empty(),
            sourceSnapshot = ResolvedSource("src/Cache.kt", "fun cache() {}", null, "v1", 8),
            hostId = "host-editor",
            hostKind = BrowserHostKind.EDITOR,
        )
        assertEquals(
            """{"navigationRequest":{"id":"request-1","direction":"next","viewRef":"view:fixed","revision":7,"elementId":"graph-main","nodeId":"node-cache"}}""",
            sourceNavigationRequest(binding, SourceStepDirection.NEXT, "request-1").toString(),
        )
    }
}
