package com.master.lessplain.ide

import com.master.lessplain.mcp.parseGraphPayload
import com.master.lessplain.model.Evidence
import com.master.lessplain.model.GraphNode
import com.master.lessplain.model.SourceAnchor
import com.master.lessplain.model.UnderstandingGraph
import com.master.lessplain.model.UnderstandingInputException
import com.master.lessplain.model.resolvedAnchors
import com.master.lessplain.storage.sha256
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphAnchorValidationTest {
    private val content = "class Service {\n    fun submit() { save() }\n}"
    private val anchor = SourceAnchor("src/Service.kt", "Service.submit", "fun submit() { save() }", "")
    private fun graph(value: SourceAnchor = anchor) = UnderstandingGraph(listOf(GraphNode("submit", "保存", anchor = value)), emptyList())
    private fun snapshot(text: String = content, symbol: String = "Service.submit") = AnchorSourceSnapshot(text) { symbol }

    @Test
    fun `missing version is filled from current document with actual line instead of hint`() {
        val parsed = parseGraphPayload("""{"nodes":[{"id":"submit","label":"保存","anchor":{"path":"src/Service.kt","symbol":"Service.submit","text":"fun submit() { save() }","lineHint":99}}],"edges":[]}""")
        val prepared = validateGraphAnchors(parsed) { snapshot() }
        val saved = prepared.nodes.single().anchor!!
        assertEquals(sha256(content), saved.version)
        assertEquals(2, saved.lineHint)
        assertEquals(2, prepared.resolvedAnchors().single()["line"])
        assertEquals("", parsed.nodes.single().anchor!!.version)
    }

    @Test
    fun `supplied version is checked including legacy CRLF hash and normalization`() {
        val crlf = content.replace("\n", "\r\n")
        val prepared = validateGraphAnchors(graph(anchor.copy(version = "sha256:" + sha256(crlf)))) { snapshot(crlf) }
        assertEquals(sha256(content), prepared.nodes.single().anchor!!.version)
        assertFailsWith<UnderstandingInputException> {
            validateGraphAnchors(graph(anchor.copy(version = "0".repeat(64)))) { snapshot() }
        }
    }

    @Test
    fun `all invalid nodes evidence and dependencies are reported without changing input`() {
        val bad = anchor.copy(text = "missing()")
        val input = graph(bad).copy(
            nodes = graph(bad).nodes + GraphNode("empty", "缺少来源"),
            evidence = listOf(Evidence("proof", anchor.copy(path = "../outside.kt"),
                dependencies = listOf(anchor.copy(path = "missing.kt")))),
        )
        val error = assertFailsWith<UnderstandingInputException> {
            validateGraphAnchors(input) { path -> if (path == anchor.path) snapshot() else null }
        }
        for (reference in listOf("nodes[submit]", "nodes[empty]", "evidence[proof].anchor", "dependencies[0]")) {
            assertTrue(error.message!!.contains(reference), error.message)
        }
        assertEquals("", input.nodes.first().anchor!!.version)
    }

    @Test
    fun `matching hash and hint cannot bypass symbol checks or ambiguity`() {
        val fixed = anchor.copy(version = sha256(content), lineHint = 2)
        assertFailsWith<UnderstandingInputException> { validateGraphAnchors(graph(fixed)) { snapshot(symbol = "Other.submit") } }
        val repeated = content + "\n" + content
        assertFailsWith<UnderstandingInputException> {
            validateGraphAnchors(graph(anchor.copy(version = sha256(repeated), lineHint = 2))) { snapshot(repeated) }
        }
    }

    @Test
    fun `evidence-only nodes use validated evidence and each file is read once`() {
        val input = UnderstandingGraph(listOf(GraphNode("a", "保存", evidenceIds = listOf("proof"))), emptyList(),
            listOf(Evidence("proof", anchor, dependencies = listOf(anchor))))
        var reads = 0
        val prepared = validateGraphAnchors(input) { reads++; snapshot() }
        assertEquals(1, reads)
        assertEquals(2, prepared.resolvedAnchors().size)
        assertTrue(prepared.evidence.all { it.anchor.version.isNotBlank() })
    }
}
