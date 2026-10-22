package com.lessplain.tutorial

import com.intellij.mcpserver.*
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.uiRead
import com.lessplain.mcp.LessplainQueryTools
import com.lessplain.mcp.LessplainMaterialTools
import com.lessplain.mcp.LessplainAnnotationTools
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 使用平台真实工具反射器生成 schema 并执行全部 15 个工具，不启动 MCP 服务。 */
class TutorialMcpTest {
    @Test fun `all structured schemas and complete create edit answer feedback workflows execute`() {
        Class.forName("org.eclipse.emf.common.util.CommonUtil")
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainTutorialMcp", true).fixture
        fixture.setUp()
        try {
            val project = fixture.project
            lateinit var tutorials: TutorialService
            lateinit var graphs: UnderstandingService
            onEdt { tutorials = project.getService(TutorialService::class.java); graphs = project.getService(UnderstandingService::class.java) }
            val loaded = CountDownLatch(1)
            graphs.readBackground({ graphs.repository }) { loaded.countDown() }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            val path = Path.of(requireNotNull(project.basePath)).resolve("Flow.txt")
            Files.createDirectories(path.parent)
            Files.writeString(path, "fun run(): Flow<Event> = TODO()")
            onEdt { uiRead {
                val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
                FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, 0), true)!!.selectionModel.setSelection(11, 22)
            } }
            val tools = listOf(LessplainQueryTools(), LessplainMaterialTools(), LessplainAnnotationTools()).flatMap { it.asTools() }.associateBy { it.descriptor.name }
            assertEquals(15, tools.size)
            assertTrue(tools.keys.all { it.startsWith("lessplain_") })
            assertTrue(tools.getValue("lessplain_tutorial_create").descriptor.inputSchema.prettyPrint().contains("\"diagrams\""))
            val seen = mutableSetOf<String>()
            fun invoke(name: String, args: String): McpToolCallResult = runBlocking {
                seen += name
                val tool = tools.getValue(name)
                val json = Json.parseToJsonElement(args).jsonObject
                val info = McpCallInfo(1, ClientInfo("test", "1"), project, tool.descriptor, json, buildJsonObject {}, McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.ASK))
                withContext(McpCallAdditionalDataElement(info)) {
                    tool.call(json)
                }
            }
            fun call(name: String, args: String): JsonObject {
                val response = invoke(name, args)
                assertFalse(response.isError, response.toString())
                val value = assertNotNull(response.structuredContent, "需要真实 structuredContent")
                assertEquals(true, value["ok"]?.jsonPrimitive?.boolean, value.toString())
                return value["data"]!!.jsonObject
            }
            val source = call("lessplain_source_resolve", """{"path":"Flow.txt","context":"fun run(): Flow<Event> = TODO()","focus":"Flow<Event>"}""")
            val sourceId = source["sourceId"]!!.jsonPrimitive.content
            val captured = call("lessplain_context_capture", """{"question":"何时执行？"}""")
            val contextId = captured["contextId"]!!.jsonPrimitive.content
            call("lessplain_context_read", """{"contextId":"$contextId"}""")
            val simulation = """{"id":"cold","language":"kotlin","code":"println(1)","scenarios":[{"id":"on","label":"执行","steps":[{"highlightLines":[1],"caption":"打印","stdout":"1\n"}]}]}"""
            val diagram = """{"id":"delivery","graph":{"nodes":[{"id":"flow","label":"Flow"}],"edges":[]}}"""
            val created = call("lessplain_tutorial_create", """{"contextId":"$contextId","title":"Flow","markdown":"# Flow\n\n旧的说明\n\n![图](lessplain://diagram/delivery)\n\n![模拟](lessplain://simulation/cold)","diagrams":[$diagram],"simulations":[$simulation]}""")
            val tutorialId = created["tutorial"]!!.jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(tutorialId, call("lessplain_tutorial_create", """{"contextId":"$contextId","title":"Flow","markdown":"# Flow\n\n旧的说明\n\n![图](lessplain://diagram/delivery)\n\n![模拟](lessplain://simulation/cold)","diagrams":[$diagram],"simulations":[$simulation]}""")["tutorial"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            call("lessplain_materials_list", """{"kind":"tutorial","query":"说明"}""")
            call("lessplain_item_read", """{"id":"$tutorialId"}""")
            call("lessplain_tutorial_edit", """{"id":"$tutorialId","expectedRevision":1,"edits":[{"find":"旧的说明","replace":"收集时执行"}]}""")
            call("lessplain_diagram_save", """{"tutorialId":"$tutorialId","id":"delivery","expectedRevision":1,"graph":{"nodes":[{"id":"flow","label":"新的 Flow"}],"edges":[]}}""")
            call("lessplain_simulation_save", """{"tutorialId":"$tutorialId","id":"cold","expectedRevision":1,"language":"kotlin","code":"println(2)","scenarios":[{"id":"on","label":"执行","steps":[{"highlightLines":[1],"caption":"打印","stdout":"2\n"}]}]}""")
            val createdGraph = call("lessplain_graph_create", """{"contextId":"$contextId","title":"运行入口","graph":{"nodes":[{"id":"flow","label":"返回事件流","sourceId":"$sourceId"}],"edges":[]}}""")
            val graphId = createdGraph["id"]!!.jsonPrimitive.content
            call("lessplain_graph_revise", """{"id":"$graphId","expectedRevision":1,"graph":{"nodes":[{"id":"flow","label":"准备事件流","sourceId":"$sourceId"}],"edges":[]}}""")
            lateinit var annotation: TutorialAnnotation
            onEdt { annotation = tutorials.newAnnotation(tutorialId, 0, 6, "question"); tutorials.store.updateAnnotation(annotation.id) { it.copy(text = "为什么？") }; tutorials.submitQuestion(annotation.id) }
            val pending = call("lessplain_annotations_list", """{"materialId":"$tutorialId","kind":"question","status":"pending"}""")
            val questionId = pending["items"]!!.jsonArray.single().jsonObject["questionId"]!!.jsonPrimitive.content
            assertTrue(pending["sources"]!!.jsonArray.isNotEmpty())
            call("lessplain_question_answer", """{"questionId":"$questionId","markdown":"因为开始收集了。","materialId":"$graphId"}""")
            assertEquals("answered", tutorials.store.annotation(annotation.id).status)
            val replayArgs = buildJsonObject {
                put("questionId", questionId); put("markdown", tutorials.store.annotation(annotation.id).answer); put("materialId", graphId)
            }.toString()
            call("lessplain_question_answer", replayArgs)
            onEdt { tutorials.deleteAnnotation(annotation.id) }
            val deletedAnswer = invoke("lessplain_question_answer", replayArgs)
            assertTrue(deletedAnswer.isError)
            assertEquals("NOT_FOUND", deletedAnswer.structuredContent?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content)
            assertTrue(tutorials.store.annotations(tutorialId).isEmpty())
            assertEquals(graphId, graphs.repository.getRecord(graphId).id)
            val frozen = graphs.core.captureContext(graphId, "flow", graphs.repository.getRecord(graphId).graph.nodes.single().anchor)
            val feedback = graphs.core.prepareQuestion("这是测试缺陷反馈", frozen.token).question
            call("lessplain_feedback_consume", """{"ids":["${feedback.id}"]}""")
            call("lessplain_feedback_consume", """{"ids":["${feedback.id}"]}""")
            assertTrue(graphs.repository.recentQuestions().none { it.id == feedback.id })
            call("lessplain_reveal", """{"id":"$tutorialId"}""")
            assertEquals(tools.keys, seen)
        } finally { onEdt { uiRead { FileEditorManager.getInstance(fixture.project).openFiles.forEach { FileEditorManager.getInstance(fixture.project).closeFile(it) } }; fixture.tearDown() } }
    }
    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)
}
