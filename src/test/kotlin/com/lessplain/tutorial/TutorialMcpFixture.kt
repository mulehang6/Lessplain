package com.lessplain.tutorial

import com.intellij.mcpserver.*
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.ide.UnderstandingService
import com.lessplain.ide.uiRead
import com.lessplain.mcp.LessplainAnnotationTools
import com.lessplain.mcp.LessplainMaterialTools
import com.lessplain.mcp.LessplainQueryTools
import com.lessplain.storage.sha256
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 真正的项目服务与工具反射器；只创建平台测试项目，不启动 IDE 窗口或 MCP 服务。 */
internal class TutorialMcpFixture(name: String) : AutoCloseable {
    private val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(name, true).fixture
    val project get() = fixture.project
    lateinit var tutorials: TutorialService
        private set
    lateinit var graphs: UnderstandingService
        private set
    private val tools = listOf(LessplainQueryTools(), LessplainMaterialTools(), LessplainAnnotationTools())
        .flatMap { it.asTools() }.associateBy { it.descriptor.name }
    val storeRoot get() = PathManager.getConfigDir().resolve("lessplain/tutorials").resolve(sha256(project.locationHash))

    init {
        Class.forName("org.eclipse.emf.common.util.CommonUtil")
        fixture.setUp()
        onEdt { tutorials = project.getService(TutorialService::class.java); graphs = project.getService(UnderstandingService::class.java) }
        val loaded = CountDownLatch(1)
        graphs.readBackground({ graphs.repository }) { loaded.countDown() }
        assertTrue(loaded.await(10, TimeUnit.SECONDS))
    }

    fun source(text: String = "fun run(): Flow<Event> = TODO()", focus: String = "Flow<Event>"): JsonObject {
        val path = Path.of(requireNotNull(project.basePath)).resolve("Flow.txt")
        Files.createDirectories(path.parent); Files.writeString(path, text)
        onEdt { uiRead {
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val editor = requireNotNull(FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file, 0), true))
            editor.selectionModel.setSelection(text.indexOf(focus), text.indexOf(focus) + focus.length)
        } }
        return call("lessplain_source_resolve", buildJsonObject { put("path", "Flow.txt"); put("context", text); put("focus", focus) })
    }

    fun invoke(name: String, arguments: JsonObject): McpToolCallResult = runBlocking {
        val tool = tools.getValue(name)
        val info = McpCallInfo(1, ClientInfo("test", "1"), project, tool.descriptor, arguments, buildJsonObject {},
            McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.ASK))
        withContext(McpCallAdditionalDataElement(info)) { tool.call(arguments) }
    }

    fun call(name: String, arguments: JsonObject): JsonObject {
        val response = invoke(name, arguments)
        assertFalse(response.isError, response.toString())
        return assertNotNull(response.structuredContent)["data"]!!.jsonObject
    }

    fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)

    override fun close() = onEdt {
        uiRead { FileEditorManager.getInstance(project).openFiles.forEach { FileEditorManager.getInstance(project).closeFile(it) } }
        fixture.tearDown()
    }
}
