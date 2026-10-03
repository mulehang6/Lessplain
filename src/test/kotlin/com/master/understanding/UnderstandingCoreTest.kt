package com.master.understanding

import com.master.understanding.core.StaticExplorer
import com.master.understanding.core.StepResult
import com.master.understanding.core.UnderstandingCore
import com.master.understanding.model.GraphEdge
import com.master.understanding.model.GraphNode
import com.master.understanding.model.BrowseState
import com.master.understanding.model.SourceAnchor
import com.master.understanding.model.UnderstandingContext
import com.master.understanding.model.UnderstandingGraph
import com.master.understanding.model.UnderstandingInputException
import com.master.understanding.model.validate
import com.master.understanding.mcp.parseGraphPayload
import com.master.understanding.ide.matchAnchorText
import com.master.understanding.ide.LocationStatus
import com.master.understanding.ide.EvidenceStatus
import com.master.understanding.ide.symbolMatches
import com.master.understanding.storage.sha256
import com.master.understanding.storage.UnderstandingRepository
import com.master.understanding.ui.FollowUpLayer
import com.master.understanding.ui.graphHierarchyEntries
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnderstandingCoreTest {
    @TempDir lateinit var root: Path

    private val graph = UnderstandingGraph(
        nodes = listOf(GraphNode("start", "入口"), GraphNode("yes", "成功"), GraphNode("no", "失败")),
        edges = listOf(
            GraphEdge("e-yes", "start", "yes", "control_flow", "有效"),
            GraphEdge("e-no", "start", "no", "control_flow", "无效"),
        ),
    )

    @Test
    fun `question remains tied to original source and writes are idempotent`() {
        val repository = UnderstandingRepository(root)
        val core = UnderstandingCore(repository)
        val source = core.captureContext(anchor = SourceAnchor("src/A.kt", "A.run", "save()", "fixed-hash", 10))
        val prepared = core.prepareQuestion("为什么保存？", source.token)
        assertTrue(prepared.payload.contains(prepared.question.id))
        assertTrue(prepared.payload.contains(source.token))

        val rootRecord = core.present(3, "create-a", "主问题", graph, "root")
        val graphContext = core.captureContext(rootRecord.id, "start", source.anchor)
        val childQuestion = core.prepareQuestion("这条分支为什么存在？", graphContext.token)
        val child = core.present(3, "create-child", childQuestion.question.text, graph, "child", questionId = childQuestion.question.id)
        assertEquals(rootRecord.id, child.parentId)
        assertEquals(rootRecord.id, repository.getQuestion(childQuestion.question.id).ownerRecordId)
        assertEquals(childQuestion.question.id, repository.questionsForRecord(rootRecord.id).single().id)
        assertEquals(child.id, repository.recordForQuestion(childQuestion.question.id)?.id)
        assertEquals(child.id, core.present(3, "create-child", childQuestion.question.text, graph, "child", questionId = childQuestion.question.id).id)
        assertFailsWith<UnderstandingInputException> {
            core.present(3, "create-child", childQuestion.question.text, graph, "child",
                questionId = childQuestion.question.id, displayTitle = "不同标题")
        }

        assertEquals("pending", repository.getQuestion(prepared.question.id).status)
        assertEquals(source.token, repository.getQuestion(prepared.question.id).contextToken)
        assertEquals(prepared.question.id, repository.questionsForContext(source.token).single().id)
        repository.saveBrowseState(BrowseState(rootRecord.id, nodeId = "yes", staticPath = listOf("e-yes"), zoom = 1.5, panX = 10.0, panY = 15.0, hasPan = true))
        repository.close()

        UnderstandingRepository(root).use { reopened ->
            assertEquals(graphContext.token, reopened.getContext(graphContext.token).token)
            assertEquals(child.id, reopened.getRecord(child.id).id)
            assertEquals(2, reopened.recentRecords().size)
            assertEquals(listOf(rootRecord.id), reopened.recentRootRecords().map { it.id })
            assertEquals(listOf(rootRecord.id), reopened.recentRootRecords("这条分支").map { it.id })
            assertTrue(reopened.recentQuestions("为什么保存").any { it.id == prepared.question.id })
            assertEquals("yes", reopened.getBrowseState(rootRecord.id)?.nodeId)
            assertEquals(1.5, reopened.getBrowseState(rootRecord.id)?.zoom)
            assertEquals(10.0, reopened.getBrowseState(rootRecord.id)?.panX)
        }
    }

    @Test
    fun `captured node context truncates or clears a route to match its node`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val routeGraph = UnderstandingGraph(
                nodes = listOf(GraphNode("a", "A"), GraphNode("b", "B"), GraphNode("c", "C"), GraphNode("d", "D")),
                edges = listOf(
                    GraphEdge("ab", "a", "b", "control_flow"),
                    GraphEdge("bc", "b", "c", "data_flow"),
                    GraphEdge("cd", "c", "d", "call"),
                ),
            )
            val record = core.present(3, "context-route", "路由上下文", routeGraph, "root")

            val earlierNode = core.captureContext(record.id, "b", staticPath = listOf("ab", "bc"))
            assertEquals(listOf("ab"), earlierNode.staticPath)

            val offRouteNode = core.captureContext(record.id, "d", staticPath = listOf("ab", "bc"))
            assertEquals(emptyList(), offRouteNode.staticPath)

            val nodeOnly = core.captureContext(record.id, "c")
            assertEquals(emptyList(), nodeOnly.staticPath)
        }
    }

    @Test
    fun `invalid references and second writer fail explicitly`() {
        UnderstandingRepository(root).use { first ->
            val core = UnderstandingCore(first)
            assertFailsWith<UnderstandingInputException> {
                core.present(3, "no-source", "问题", graph, "child")
            }
            assertFailsWith<UnderstandingInputException> { core.context("missing") }
            UnderstandingRepository(root).use { second ->
                assertTrue(second.readOnly)
                assertFailsWith<IllegalStateException> { UnderstandingCore(second).captureContext() }
                assertFalse(first.readOnly)
            }
            assertFailsWith<UnderstandingInputException> {
                first.putContext(UnderstandingContext("other", "different-project", null, null, null, null, emptyList(), 0))
            }
        }
    }

    @Test
    fun `present from copied node context creates a visible child question`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val parent = core.present(3, "parent", "主图", graph, "root")
            val context = core.captureContext(parent.id, "start")
            val child = core.present(3, "copied-node-answer", "这一行做什么？", graph,
                "child", contextToken = context.token)
            assertEquals(parent.id, child.parentId)
            val savedQuestion = repository.questionsForRecord(parent.id).single()
            assertEquals(child.questionId, savedQuestion.id)
            assertEquals("start", repository.getContext(savedQuestion.contextToken).nodeId)
            assertEquals(child.id, repository.recordForQuestion(savedQuestion.id)?.id)
            assertEquals(child.id, core.present(3, "copied-node-answer", "这一行做什么？", graph,
                "child", contextToken = context.token).id)
        }
    }

    @Test
    fun `static exploration waits at branch and can choose another route after back`() {
        val explorer = StaticExplorer(graph)
        val pending = explorer.next()
        assertTrue(pending is StepResult.Choose)
        assertEquals("start", explorer.currentNodeId)
        assertEquals(StepResult.Moved("yes"), explorer.choose("e-yes"))
        assertTrue(explorer.back())
        assertEquals(StepResult.Moved("no"), explorer.choose("e-no"))
        assertEquals(listOf("e-no"), explorer.path)
    }

    @Test
    fun `static exploration follows calls and restores a clicked node`() {
        val flow = UnderstandingGraph(
            nodes = listOf(GraphNode("input", "读取参数"), GraphNode("lookup", "查找用户"), GraphNode("ok", "成功"), GraphNode("fail", "失败")),
            edges = listOf(
                GraphEdge("call-login", "input", "lookup", "call"),
                GraphEdge("found", "lookup", "ok", "control_flow", "找到用户"),
                GraphEdge("missing", "lookup", "fail", "control_flow", "用户不存在"),
            ),
        )
        val explorer = StaticExplorer(flow)
        assertEquals("input", explorer.currentNodeId)
        assertEquals(StepResult.Moved("lookup"), explorer.next())
        assertTrue(explorer.next() is StepResult.Choose)
        assertTrue(explorer.jumpTo("ok"))
        assertEquals(emptyList(), explorer.path)
        assertEquals("ok", explorer.currentNodeId)
        assertTrue(explorer.back())
        assertEquals("lookup", explorer.currentNodeId)
        assertEquals("ok", StaticExplorer(flow, savedNodeId = "ok").currentNodeId)
    }

    @Test
    fun `example graph steps through value transfer and waits at a missing parameter branch`() {
        val example = UnderstandingGraph(
            nodes = listOf(
                GraphNode("request", "请求", "example"), GraphNode("read", "读取"),
                GraphNode("value", "值", "example"), GraphNode("missing", "缺失", "example"),
            ),
            edges = listOf(
                GraphEdge("input", "request", "read", "data_flow"),
                GraphEdge("present", "read", "value", "control_flow", "存在"),
                GraphEdge("absent", "read", "missing", "control_flow", "缺失"),
            ),
        )
        val explorer = StaticExplorer(example)
        assertEquals(StepResult.Moved("read"), explorer.next())
        assertTrue(explorer.next() is StepResult.Choose)
        assertEquals(StepResult.Moved("value"), explorer.choose("present"))
    }

    @Test
    fun `stepping in a disconnected follow up cluster restores its own route`() {
        val twoQuestions = UnderstandingGraph(
            nodes = listOf(GraphNode("first", "单节点追问"), GraphNode("request", "示例请求", "example"),
                GraphNode("read", "读取参数"), GraphNode("result", "返回值", "example")),
            edges = listOf(
                GraphEdge("read-edge", "request", "read", "data_flow"),
                GraphEdge("result-edge", "read", "result", "control_flow"),
            ),
        )
        val explorer = StaticExplorer(twoQuestions, savedNodeId = "result")
        assertEquals(emptyList(), explorer.path)
        assertEquals("result", explorer.currentNodeId)
        assertEquals(StepResult.End, explorer.next())
        assertTrue(explorer.jumpTo("request"))
        assertEquals(emptyList(), explorer.path)
        assertEquals(StepResult.Moved("read"), explorer.next())
        assertEquals(StepResult.Moved("result"), explorer.next())
        assertEquals(listOf("read-edge", "result-edge"), explorer.path)
        assertTrue(explorer.back())
        assertEquals("read", explorer.currentNodeId)
        val restored = StaticExplorer(twoQuestions, explorer.path, explorer.currentNodeId)
        assertEquals("read", restored.currentNodeId)
        assertEquals(StepResult.Moved("result"), restored.next())
        assertTrue(restored.jumpTo("first"))
        assertEquals(emptyList(), restored.path)
        assertEquals(StepResult.End, restored.next())
        val outOfOrder = UnderstandingGraph(twoQuestions.nodes.drop(1).reversed(), twoQuestions.edges)
        val orderedExplorer = StaticExplorer(outOfOrder)
        assertEquals("request", orderedExplorer.currentNodeId)
        assertEquals(StepResult.Moved("read"), orderedExplorer.next())
    }

    @Test
    fun `nested question graph traverses data flow without example node kinds`() {
        val nested = UnderstandingGraph(
            nodes = listOf(
                GraphNode("request", "HTTP request", "input"),
                GraphNode("read", "getParameter", "step"),
                GraphNode("value", "parameter value", "result"),
                GraphNode("missing", "missing parameter", "result"),
                GraphNode("assign", "assign username", "step"),
                GraphNode("login", "login", "call"),
            ),
            edges = listOf(
                GraphEdge("e1", "request", "read", "data_flow"),
                GraphEdge("e2", "read", "value", "control_flow"),
                GraphEdge("e3", "read", "missing", "control_flow"),
                GraphEdge("e4", "value", "assign", "data_flow"),
                GraphEdge("e5", "assign", "login", "data_flow"),
            ),
        )
        val explorer = StaticExplorer(nested)
        assertEquals("request", explorer.currentNodeId)
        assertEquals(StepResult.Moved("read"), explorer.next())
        assertTrue(explorer.next() is StepResult.Choose)
        assertEquals(StepResult.Moved("value"), explorer.choose("e2"))
        assertTrue(explorer.back())
        assertEquals("read", explorer.currentNodeId)
        assertEquals(StepResult.Moved("value"), explorer.choose("e2"))
        assertEquals(StepResult.Moved("assign"), explorer.next())
        assertEquals(StepResult.Moved("login"), explorer.next())
        assertEquals(StepResult.End, explorer.next())

        val restoredAtBranch = StaticExplorer(nested, savedNodeId = "read")
        assertEquals(emptyList(), restoredAtBranch.path)
        assertTrue(restoredAtBranch.back())
        assertEquals("request", restoredAtBranch.currentNodeId)
    }

    @Test
    fun `hierarchy includes descendants below the current question and sibling branches`() {
        UnderstandingRepository(root).use { repository ->
            val core = UnderstandingCore(repository)
            val parent = core.present(3, "hierarchy-root", "主问题", graph, "root")
            val child = core.present(3, "hierarchy-child", "第一层", graph, "child",
                contextToken = core.captureContext(parent.id, "start").token)
            val grandchild = core.present(3, "hierarchy-grandchild", "第二层", graph, "child",
                contextToken = core.captureContext(child.id, "yes").token)
            core.present(3, "hierarchy-great-grandchild", "第三层", graph, "child",
                contextToken = core.captureContext(grandchild.id, "no").token)
            core.present(3, "hierarchy-sibling", "另一分支", graph, "child",
                contextToken = core.captureContext(parent.id, "no").token)

            val entries = graphHierarchyEntries(repository, parent.id)
            assertEquals(listOf(0, 1, 2, 3, 1), entries.map { it.depth })
            assertEquals(listOf(
                emptyList(),
                listOf(FollowUpLayer(parent.id, "start")),
                listOf(FollowUpLayer(parent.id, "start"), FollowUpLayer(child.id, "yes")),
                listOf(FollowUpLayer(parent.id, "start"), FollowUpLayer(child.id, "yes"),
                    FollowUpLayer(grandchild.id, "no")),
                listOf(FollowUpLayer(parent.id, "no")),
            ), entries.map { it.layers })
        }
    }

    @Test
    fun `failed migration leaves old data and a backup`() {
        val dbPath = root.resolve(".idea/code-understanding/understanding.db")
        Files.createDirectories(dbPath.parent)
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { db ->
            db.createStatement().use { statement ->
                statement.execute("CREATE TABLE contexts (legacy_value TEXT)")
                statement.execute("INSERT INTO contexts VALUES ('keep-me')")
            }
        }
        assertFailsWith<IllegalStateException> { UnderstandingRepository(root) }
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT legacy_value FROM contexts").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("keep-me", rows.getString(1))
                }
            }
        }
        assertTrue(Files.list(dbPath.parent).use { files ->
            files.anyMatch { it.fileName.toString().startsWith("understanding.db.before-v0-") }
        })
    }

    @Test
    fun `graph payload accepts omitted optional fields but rejects coordinates and invalid branches`() {
        val parsed = parseGraphPayload(
            """{"nodes":[{"id":"a","label":"判断"},{"id":"b","label":"保存"}],"edges":[{"id":"go","from":"a","to":"b","relation":"control_flow"}]}""",
        )
        parsed.validate()
        assertEquals(emptyList(), parsed.evidence)
        assertFailsWith<UnderstandingInputException> {
            parseGraphPayload("""{"nodes":[{"id":"a","label":"判断","x":10}],"edges":[]}""")
        }
        assertFailsWith<UnderstandingInputException> {
            graph.copy(edges = graph.edges.map { it.copy(condition = null) }).validate()
        }
    }

    @Test
    fun `anchor matching distinguishes inserted lines duplicate text and another symbol`() {
        val original = "fun save() {\n    persist()\n}"
        val anchor = SourceAnchor("src/A.kt", "save", "persist()", sha256(original), 2)
        assertEquals(
            LocationStatus.EXACT,
            matchAnchorText(anchor, original) { "save" }.location,
        )
        val crlfVersion = anchor.copy(version = "sha256:${sha256(original.replace("\n", "\r\n")).uppercase()}")
        assertEquals(EvidenceStatus.UNCHANGED, matchAnchorText(crlfVersion, original) { "save" }.evidence)
        val inserted = "// header\n$original"
        val moved = matchAnchorText(anchor, inserted) { "save" }
        assertEquals(LocationStatus.RELOCATED, moved.location)
        assertEquals(EvidenceStatus.UNKNOWN, moved.evidence)
        assertEquals(LocationStatus.AMBIGUOUS, matchAnchorText(anchor, "$inserted\npersist()") { "save" }.location)
        assertEquals(LocationStatus.UNRESOLVED, matchAnchorText(anchor, inserted) { "otherFunction" }.location)
        assertEquals(EvidenceStatus.CHANGED, matchAnchorText(anchor, original.replace("persist()", "queue()")) { "save" }.evidence)
        assertTrue(symbolMatches("A.save", "A.save"))
        assertTrue(symbolMatches("save", "A.save"))
        assertTrue(symbolMatches("AdminLoginController.login(HttpServletRequest,HttpServletResponse)", "AdminLoginController.login.username"))
        assertFalse(symbolMatches("A.save", "B.save"))
    }

    @Test
    fun `multi-line source anchor includes the complete logic block`() {
        val source = "fun save() {\n    if (ready) {\n        persist()\n    }\n}"
        val block = "if (ready) {\n        persist()\n    }"
        val anchor = SourceAnchor("src/A.kt", "save", block, sha256(source), 2)
        val located = matchAnchorText(anchor, source) { "save" }
        assertEquals(LocationStatus.EXACT, located.location)
        assertEquals(block, source.substring(located.offset!!, located.offset + block.length))
    }
}
