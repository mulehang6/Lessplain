package com.lessplain.lesson

import com.lessplain.model.GraphNode
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import com.lessplain.storage.sha256
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LessonEngineTest {
    @TempDir lateinit var temporary: Path
    private fun workspace(name: String = "project-a") = LessonWorkspace(temporary.resolve(name))
    private val origin = LessonOrigin(SourceAnchor("src/Gateway.kt", "Gateway.run", "Flow<AgentStreamEvent>", "source-v1", 79))
    private val paragraph = LessonBlock("purpose", "text", "Flow 按顺序提供值。")

    @Test fun `legacy JSON and request receipt remain usable after optional fields are added`() {
        val workspace = workspace()
        val (engine, record) = complete(workspace)
        engine.addAnnotation(record.id, target(), note = "旧笔记")
        val stateFile = workspace.root.resolve("lessons.json")
        val json = com.google.gson.JsonParser.parseString(Files.readString(stateFile)).asJsonObject
        val saved = json.getAsJsonArray("lessons").single().asJsonObject
        saved.getAsJsonObject("origin").remove("focus")
        saved.getAsJsonArray("blocks").forEach { block ->
            block.asJsonObject.remove("richText"); block.asJsonObject.remove("sourceBindings")
        }
        val token = workspace.snapshot.contexts.first().token
        val oldBlock = "[LessonBlock(id=purpose, kind=text, text=Flow 按顺序提供值。, level=0, language=kotlin, exampleId=null, columns=[], rows=[], graph=null, source=null)]"
        json.getAsJsonArray("receipts").single().asJsonObject.addProperty("fingerprint", sha256("$token|认识 Flow|$oldBlock|[]|false|null"))
        Files.writeString(stateFile, json.toString())
        val loaded = LessonWorkspace(workspace.root).apply { load() }
        assertEquals(record, LessonEngine(loaded).present("initial", token, "认识 Flow", listOf(paragraph), emptyList(), false))
        assertEquals("旧笔记", loaded.snapshot.annotations.single().note)
        assertEquals(null, loaded.snapshot.lessons.single().blocks.single().sourceBindings)
    }

    private fun complete(workspace: LessonWorkspace): Pair<LessonEngine, LessonRecord> {
        val engine = LessonEngine(workspace)
        val token = engine.start(origin, "Flow", "Flow 有什么作用？")
        return engine to engine.present("initial", token.token, "认识 Flow", listOf(paragraph), emptyList(), false)
    }

    private fun target(text: String = paragraph.text, start: Int = 0, end: Int = 4) =
        LessonTarget("purpose", start = start, end = end, selectedText = text.substring(start, end), contentVersion = sha256(text))

    @Test fun `parallel answers keep their frozen targets and personal notes`() {
        val workspace = workspace()
        val (engine, record) = complete(workspace)
        val first = engine.addAnnotation(record.id, target(), "Flow 是什么？")
        val second = engine.addAnnotation(record.id, target(start = 5, end = 7), "顺序是什么意思？")
        engine.updateNote(first.id, "我自己的理解，不允许 AI 覆盖")
        engine.start(origin.copy(anchor = origin.anchor.copy(path = "Other.kt")), "另一处", "另一个问题")
        engine.present("second", second.contextToken!!, "", listOf(LessonBlock("answer-b", "text", "第二处解释")), emptyList(), true)
        engine.present("first", first.contextToken!!, "", listOf(LessonBlock("answer-a", "text", "第一处解释")), emptyList(), true)
        val loaded = LessonWorkspace(workspace.root).load()
        val restored = loaded.annotations.first { it.id == first.id }
        assertEquals("我自己的理解，不允许 AI 覆盖", restored.note)
        assertEquals("第一处解释", restored.answer.single().text)
        assertEquals("第二处解释", loaded.annotations.first { it.id == second.id }.answer.single().text)
        assertTrue(loaded.annotations.all { it.status == "answered" })
    }

    @Test fun `retry is idempotent and a reused request id rejects different content`() {
        val workspace = workspace()
        val (engine, record) = complete(workspace)
        val annotation = engine.addAnnotation(record.id, target(), "继续解释")
        val answer = listOf(LessonBlock("answer", "text", "一份解释"))
        engine.present("reply", annotation.contextToken!!, "", answer, emptyList(), true)
        engine.present("reply", annotation.contextToken, "", answer, emptyList(), true)
        assertEquals(1, workspace.snapshot.receipts.count { it.requestId == "reply" })
        assertFailsWith<IllegalStateException> {
            engine.present("reply", annotation.contextToken, "", listOf(LessonBlock("answer", "text", "另一份")), emptyList(), true)
        }
    }

    @Test fun `changed content refuses retargeting and preserves the pending question`() {
        val workspace = workspace()
        val (engine, record) = complete(workspace)
        val annotation = engine.addAnnotation(record.id, target(), "这里什么意思", "已有笔记")
        workspace.update { state -> state.copy(lessons = state.lessons.map {
            if (it.id == record.id) it.copy(blocks = listOf(paragraph.copy(text = "另一段内容"))) else it
        }) }
        assertFailsWith<IllegalStateException> {
            engine.present("stale", annotation.contextToken!!, "", listOf(LessonBlock("answer", "text", "解释")), emptyList(), true)
        }
        assertEquals("pending", workspace.snapshot.annotations.single().status)
        assertEquals("已有笔记", workspace.snapshot.annotations.single().note)
        assertFalse(workspace.snapshot.receipts.any { it.requestId == "stale" })
    }

    @Test fun `a followup can target text inside a previous answer`() {
        val workspace = workspace()
        val (engine, record) = complete(workspace)
        val first = engine.addAnnotation(record.id, target(), "Flow 是什么？")
        val reply = LessonBlock("cold", "text", "冷流在收集时运行。")
        engine.present("reply", first.contextToken!!, "", listOf(reply), emptyList(), true)
        val childTarget = LessonTarget("cold", first.id, 0, 2, "冷流", sha256(reply.text))
        val child = engine.addAnnotation(record.id, childTarget, "冷流又是什么？")
        assertEquals(first.id, child.target.annotationId)
        val otherContext = engine.start(origin, "另一课", "别的来源")
        val other = engine.present("other", otherContext.token, "另一课", listOf(paragraph), emptyList(), false)
        assertFailsWith<IllegalStateException> { engine.addAnnotation(other.id, childTarget, "不能跨教材误挂") }
    }

    @Test fun `invalid example references do not save a partial answer`() {
        val workspace = workspace()
        val engine = LessonEngine(workspace)
        val context = engine.start(origin, "Flow", "学习")
        val before = workspace.snapshot
        assertFailsWith<IllegalArgumentException> {
            engine.present("invalid", context.token, "Flow", listOf(LessonBlock("code", "code", exampleId = "missing")), emptyList(), false)
        }
        assertEquals(before, workspace.snapshot)
        assertFalse(Files.exists(workspace.root.resolve("examples")))
    }

    @Test fun `running files are external self contained and learner edits survive reopening`() {
        val workspace = workspace()
        val engine = LessonEngine(workspace)
        val context = engine.start(origin, "Flow", "学习")
        val example = LessonExample("numbers", "numbers.main.kts", "println(1)", listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"))
        val record = engine.present("example", context.token, "Flow", listOf(LessonBlock("sample", "code", exampleId = example.id)), listOf(example), false)
        val file = workspace.examplePath(record, example)
        assertTrue(Files.readString(file).startsWith("@file:DependsOn(\"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2\")"))
        Files.writeString(file, "println(42)\n")
        workspace.materializeExamples(record)
        assertEquals("println(42)\n", Files.readString(file))
        val selection = LessonTarget("sample", start = 0, end = 7, selectedText = "println", contentVersion = sha256(Files.readString(file)))
        val question = engine.addAnnotation(record.id, selection, "这个输出是什么")
        Files.writeString(file, "println(100)\n")
        assertFalse(engine.targetIsCurrent(record, question.target))
        assertEquals(record, LessonWorkspace(workspace.root).load().lessons.single())
    }

    @Test fun `example paths cannot escape the lesson directory`() {
        val workspace = workspace()
        val engine = LessonEngine(workspace)
        val token = engine.start(origin, "Flow", "学习")
        assertFailsWith<IllegalArgumentException> {
            engine.present("escape", token.token, "Flow", listOf(paragraph),
                listOf(LessonExample("sample", "../../escape.main.kts", "println(1)")), false)
        }
        assertFalse(Files.exists(temporary.resolve("escape.main.kts")))
    }

    @Test fun `source location requires a unique excerpt instead of the saved line hint`() {
        assertEquals(4, uniqueLessonSourceOffset("new\nFlow<T>\nend", "Flow<T>"))
        assertEquals(null, uniqueLessonSourceOffset("Flow<T>\nFlow<T>", "Flow<T>"))
        assertEquals(null, uniqueLessonSourceOffset("Flow<Other>", "Flow<T>"))
    }

    @Test fun `browse position and collapsed annotations persist without crossing projects`() {
        val workspace = workspace()
        val (engine, record) = complete(workspace)
        val state = LessonBrowse("root/purpose", 37, setOf("some-note"))
        engine.saveBrowse(record.id, state)
        assertEquals(state, LessonWorkspace(workspace.root).load().browse[record.id])
        assertTrue(workspace("project-b").load().lessons.isEmpty())
    }

    @Test fun `corrupt stored data fails without replacing it with an empty workspace`() {
        val workspace = workspace()
        Files.createDirectories(workspace.root)
        val path = workspace.root.resolve("lessons.json")
        Files.writeString(path, "{broken")
        assertFailsWith<Exception> { workspace.load() }
        assertEquals("{broken", Files.readString(path))
    }

    @Test fun `table cell and diagram node selections retain precise object identity`() {
        val table = LessonBlock("members", "table", columns = listOf("成员", "作用"), rows = listOf(listOf("context", "上下文")))
        val graph = LessonBlock("figure", "diagram", graph = UnderstandingGraph(listOf(GraphNode("resume", "恢复执行")), emptyList()))
        val record = LessonRecord("lesson", 1, "课", origin, listOf(table, graph), createdAt = 0)
        assertTrue(lessonTargetIsCurrent(record, emptyList(), LessonTarget("members", start = 0, end = 2,
            selectedText = "上下", contentVersion = sha256("上下文"), row = 0, column = 1)))
        assertTrue(lessonTargetIsCurrent(record, emptyList(), LessonTarget("figure", start = 0, end = 4,
            selectedText = "恢复执行", contentVersion = sha256("恢复执行"), objectId = "resume")))
    }

    @Test fun `a delayed note write cannot replace the latest learner input`() {
        val workspace = workspace()
        val (engine, record) = complete(workspace)
        val annotation = engine.addAnnotation(record.id, target(), note = "初稿")
        val latest = "最新笔记"
        engine.updateNote(annotation.id, latest) { true }
        engine.updateNote(annotation.id, "旧笔记") { latest == "旧笔记" }
        assertEquals("最新笔记", LessonWorkspace(workspace.root).load().annotations.single().note)
    }

    @Test fun `a reply preserves the last note flushed while its example is being checked`() {
        val workspace = workspace()
        var flushNote: (() -> Unit)? = null
        val engine = LessonEngine(workspace, readExample = { record, example ->
            flushNote?.invoke()
            workspace.exampleText(record, example)
        })
        val context = engine.start(origin, "Flow", "学习")
        val example = LessonExample("events", "events.main.kts", "println(1)")
        val record = engine.present("initial", context.token, "Flow", listOf(LessonBlock("code", "code", exampleId = "events")), listOf(example), false)
        val text = workspace.exampleText(record, example)
        val selection = LessonTarget("code", start = 0, end = 7, selectedText = "println", contentVersion = sha256(text))
        val annotation = engine.addAnnotation(record.id, selection, "解释这句", "旧笔记")
        flushNote = { workspace.update { state -> state.copy(annotations = state.annotations.map {
            if (it.id == annotation.id) it.copy(note = "关闭项目时的最后输入") else it
        }) } }
        engine.present("reply", annotation.contextToken!!, "", listOf(LessonBlock("answer", "text", "输出一个值")), emptyList(), true)
        assertEquals("关闭项目时的最后输入", LessonWorkspace(workspace.root).load().annotations.single().note)
        assertEquals("answered", workspace.snapshot.annotations.single().status)
    }
}
