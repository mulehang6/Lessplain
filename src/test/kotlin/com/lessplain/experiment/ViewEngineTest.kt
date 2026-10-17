package com.lessplain.experiment

import com.lessplain.experiment.core.ViewEngine
import com.lessplain.experiment.browser.freezeSourceNavigation
import com.lessplain.experiment.model.*
import com.lessplain.experiment.storage.ViewStore
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.*

class ViewEngineTest {
    @TempDir lateinit var directory: Path
    private fun document(question: String = "这个对象是什么？", title: String? = null) =
        ViewDocument(question, JsonPayload(Json.parseToJsonElement("""{"root":"concept","elements":{"concept":{"type":"Text","props":{"text":"只解释当前对象"},"children":[]}}}""").jsonObject), title)
    private fun engine(store: ViewStore, validator: (ViewDocument) -> Unit = {}) =
        ViewEngine(store, { JsonPayload.empty() }, validator) { sources -> sources.mapValues {
            ResolvedSource(it.value.path, it.value.text, it.value.symbol, version = "version", line = 1)
        } }

    private fun sourceDocument(question: String = "图中节点如何工作？") = ViewDocument(
        question = question,
        spec = JsonPayload(Json.parseToJsonElement(
            """{"root":"graph","elements":{"graph":{"type":"Graph","props":{"nodes":[{"id":"node-1","label":"目标节点","source":"source-1"}]},"children":[]}}}""",
        ).jsonObject),
        sources = mapOf("source-1" to SourceReference("src/Example.kt", "fun target() {}", "target")),
    )

    private fun createSourceView(engine: ViewEngine): ViewRecord {
        val context = engine.context(null, ViewOrigin())
        val receipt = engine.present(context.context, sourceDocument())
        return engine.snapshot().views.single { it.ref == receipt.ref }
    }

    private fun frozenBinding(engine: ViewEngine, view: ViewRecord): EditorSourceBinding =
        engine.freezeSourceBinding(
            view.ref, view.revision, "graph", "node-1", "source-1", "目标节点",
            JsonPayload(Json.parseToJsonElement("""{"graphs":{"graph":{"selectedNode":"node-1"}}}""").jsonObject),
        )

    private fun sourceOrigin(binding: EditorSourceBinding): ViewOrigin = ViewOrigin(
        viewRef = binding.viewRef,
        elementId = binding.elementId,
        nodeId = binding.nodeId,
        nodeLabel = binding.label.takeIf { binding.nodeId != null },
        source = binding.sourceSnapshot,
        state = binding.state,
    )

    private fun sourceReferences() = mapOf(
        "source-1" to SourceReference("src/Example.kt", "fun target() {}", "target"),
        "source-2" to SourceReference("src/Example.kt", "fun related() {}", "related"),
    )

    private fun componentDocument(
        type: String,
        props: String,
        sources: Map<String, SourceReference> = sourceReferences(),
        demos: Map<String, DemoResource>? = null,
    ) = ViewDocument(
        question = "组件源码如何工作？",
        spec = JsonPayload(Json.parseToJsonElement(
            """{"root":"component","elements":{"component":{"type":"$type","props":$props,"children":[]}}}""",
        ).jsonObject),
        sources = sources,
        demos = demos,
    )

    private fun createView(engine: ViewEngine, document: ViewDocument): ViewRecord {
        val receipt = engine.present(engine.context(null, ViewOrigin()).context, document)
        return engine.snapshot().views.single { it.ref == receipt.ref }
    }

    private fun sourceParams(view: ViewRecord, elementId: String? = "component", state: String = "{}") =
        buildJsonObject {
            put("revision", view.revision)
            elementId?.let { put("elementId", it) }
            put("state", Json.parseToJsonElement(state))
        }

    private fun graphWithEvidence(evidence: String) = ViewDocument(
        question = "关系证据如何工作？",
        spec = JsonPayload(Json.parseToJsonElement(
            """{"root":"graph","elements":{"graph":{"type":"Graph","props":{"nodes":[{"id":"node-1","label":"起点","source":"source-1"},{"id":"node-2","label":"终点","source":"source-1"}],"edges":[{"id":"edge-1","source":"node-1","target":"node-2","evidence":[$evidence]}]},"children":[]}}}""",
        ).jsonObject),
        sources = sourceReferences(),
    )

    @Test fun retryAfterRestart() {
        val file = directory.resolve("views.db")
        lateinit var receipt: PresentReceipt
        lateinit var ticket: ContextResult
        ViewStore(file).use { store ->
            val engine = engine(store)
            ticket = engine.context(null, ViewOrigin())
            receipt = engine.present(ticket.context, document("  这个对象是什么？ "))
            assertEquals(receipt, engine.present(ticket.context, document()))
            assertFailsWith<IllegalStateException> { engine.present(ticket.context, document(title = "其他标题")) }
        }
        ViewStore(file).use { store ->
            assertEquals(receipt, engine(store).present(ticket.context, document()))
            assertEquals(1, engine(store).snapshot().views.size)
        }
    }
    @Test fun failedValidationPreservesQuestion() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val root = engine.present(engine.context(null, ViewOrigin()).context, document())
            val note = engine.createNote("为什么返回 JSON？", "question", ViewOrigin(viewRef = root.ref, nodeId = "cache", state = JsonPayload(buildJsonObject { put("hit", true) })))
            val context = engine.context(note.ref, ViewOrigin(viewRef = "later"))
            assertEquals(root.ref, context.originView?.ref)
            assertNull(context.view)
            val rejecting = engine(store) { error("非法组件") }
            assertFailsWith<IllegalStateException> { rejecting.present(context.context, document(note.text)) }
            assertEquals("pending", engine.snapshot().notes.single().status)
            assertEquals(1, engine.snapshot().views.size)
            val answer = engine.present(context.context, document(note.text))
            val saved = engine.snapshot().views.first { it.ref == answer.ref }
            assertEquals(root.ref, saved.parentRef)
            assertEquals("cache", saved.origin.nodeId)
            assertEquals(true, saved.origin.state.value["hit"]?.jsonPrimitive?.boolean)
            assertTrue(engine.snapshot().notes.isEmpty())
            assertEquals(note.ref, saved.answeredNote?.ref)
        }
    }

    @Test fun freezesGraphSourceBindingAndCreatesNoteAgainstItsSnapshot() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val view = createSourceView(engine)
            val binding = frozenBinding(engine, view)
            assertEquals(view.ref, binding.viewRef)
            assertEquals(view.revision, binding.revision)
            assertEquals("graph", binding.elementId)
            assertEquals("node-1", binding.nodeId)
            assertEquals("source-1", binding.sourceId)
            assertEquals("目标节点", binding.label)
            assertEquals(view.sources.getValue("source-1"), binding.sourceSnapshot)

            val note = engine.createNote("这个函数负责什么？", "question", sourceOrigin(binding), binding)
            assertEquals(binding.viewRef, note.origin.viewRef)
            assertEquals(binding.elementId, note.origin.elementId)
            assertEquals(binding.nodeId, note.origin.nodeId)
            assertEquals(binding.label, note.origin.nodeLabel)
            assertEquals(binding.state, note.origin.state)
            assertEquals(1, engine.snapshot().notes.size)
        }
    }

    @Test fun staleEditorBindingAndNodeIdentityFailWithoutWritingNotes() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val view = createSourceView(engine)
            val binding = frozenBinding(engine, view)
            val origin = sourceOrigin(binding)
            val invalid = listOf(
                binding.copy(revision = binding.revision - 1),
                binding.copy(elementId = "missing"),
                binding.copy(nodeId = "missing"),
                binding.copy(label = "旧标签"),
                binding.copy(sourceId = "other-source"),
                binding.copy(sourceSnapshot = binding.sourceSnapshot.copy(version = "old-version")),
            )
            invalid.forEach { candidate ->
                assertFailsWith<IllegalStateException> {
                    engine.createNote("保留的草稿", "question", sourceOrigin(candidate), candidate)
                }
            }
            assertTrue(engine.snapshot().notes.isEmpty())

            engine.rename(view.ref, "已更新页面")
            assertFailsWith<IllegalStateException> {
                engine.createNote("保留的草稿", "question", origin, binding)
            }
            assertTrue(engine.snapshot().notes.isEmpty())
        }
    }

    @Test fun editorQuestionRevalidatesTheFrozenDocumentBeforeWriting() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val view = createSourceView(engine)
            val binding = frozenBinding(engine, view)
            val changedSourceEngine = ViewEngine(
                store,
                { JsonPayload.empty() },
                {},
            ) { sources ->
                sources.mapValues { ResolvedSource(it.value.path, it.value.text, it.value.symbol, "changed", 1) }
            }
            assertFailsWith<IllegalStateException> {
                changedSourceEngine.createNote("源码改变时也保留草稿", "question", sourceOrigin(binding), binding)
            }
            assertTrue(engine.snapshot().notes.isEmpty())
        }
    }

    @Test fun explicitSourceComponentNavigationFreezesItsOwnElementAndState() {
        listOf(
            "SourceLink" to """{"source":"source-1","label":"源码入口"}""",
            "CodeExcerpt" to """{"source":"source-1","label":"关键片段"}""",
        ).forEach { (type, props) ->
            ViewStore(directory.resolve("$type.db")).use { store ->
                val engine = engine(store)
                val view = createView(engine, componentDocument(type, props))
                val state = """{"selected":"$type"}"""
                val binding = freezeSourceNavigation(
                    engine, view, "source-1", sourceParams(view, state = state),
                    fallbackState = JsonPayload(Json.parseToJsonElement("""{"wrong":"currentOrigin"}""").jsonObject),
                )
                assertEquals("component", binding.elementId)
                assertNull(binding.nodeId)
                assertEquals(type, binding.componentType)
                assertEquals("source-1", binding.sourceId)
                assertEquals(type, binding.state.value["selected"]?.jsonPrimitive?.content)

                val note = engine.createNote("问组件源码", "question", sourceOrigin(binding), binding)
                assertEquals("component", note.origin.elementId)
                assertNull(note.origin.nodeId)
                assertNull(note.origin.nodeLabel)
                assertEquals(binding.state, note.origin.state)
            }
        }
    }

    @Test fun explicitBareSourceRequiresCurrentRevisionAndDoesNotBorrowGraphIdentity() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val view = createSourceView(engine)
            val state = JsonPayload(Json.parseToJsonElement("""{"lesson":"selected"}""").jsonObject)
            val binding = freezeSourceNavigation(
                engine, view, "source-1", sourceParams(view, elementId = null, state = """{"lesson":"selected"}"""),
                fallbackState = JsonPayload(Json.parseToJsonElement("""{"graph":"stale"}""").jsonObject),
            )
            assertNull(binding.elementId)
            assertNull(binding.nodeId)
            assertEquals(state, binding.state)
            val note = engine.createNote("问课程源码", "question", sourceOrigin(binding), binding)
            assertNull(note.origin.elementId)
            assertNull(note.origin.nodeId)
            assertNull(note.origin.nodeLabel)

            assertFailsWith<IllegalStateException> {
                freezeSourceNavigation(engine, view, "source-1", buildJsonObject {
                    put("elementId", "component")
                    put("state", JsonObject(emptyMap()))
                }, fallbackState = state)
            }
            assertFailsWith<IllegalStateException> {
                freezeSourceNavigation(engine, view, "source-1", buildJsonObject {
                    put("elementId", "component")
                    put("revision", view.revision + 1)
                    put("state", JsonObject(emptyMap()))
                })
            }
            assertEquals(1, engine.snapshot().notes.size)
        }
    }

    @Test fun componentAndDemoBindingsRevalidateTheirActualReferencesOnSave() {
        ViewStore(directory.resolve("component.db")).use { store ->
            val engine = engine(store)
            val original = componentDocument("SourceLink", """{"source":"source-1","label":"入口"}""")
            val view = createView(engine, original)
            val binding = engine.freezeSourceBinding(
                view.ref, view.revision, "component", null, "source-1", "入口", JsonPayload.empty(),
            )
            val changed = componentDocument("SourceLink", """{"source":"source-2","label":"入口"}""")
            store.transaction {
                put("views", view.ref, store.json.encodeToString(view.copy(document = changed)))
            }
            assertFailsWith<IllegalStateException> {
                engine.createNote("组件已换源", "question", sourceOrigin(binding), binding)
            }
            assertTrue(engine.snapshot().notes.isEmpty())
        }

        ViewStore(directory.resolve("demo.db")).use { store ->
            val engine = engine(store)
            val demos = mapOf("demo-1" to DemoResource("<pre>first()</pre>"), "demo-2" to DemoResource("<pre>next()</pre>"))
            val original = componentDocument("Demo", """{"demo":"demo-1","title":"运行示例"}""", demos = demos)
            val view = createView(engine, original)
            val binding = engine.freezeSourceBinding(
                view.ref, view.revision, "component", null, "source-1", "运行示例", JsonPayload.empty(),
            )
            assertEquals("Demo", binding.componentType)
            assertEquals("demo-1", binding.demoId)
            val saved = engine.createNote("问有效示例", "question", sourceOrigin(binding), binding)
            assertEquals("Demo", binding.componentType)
            assertEquals(binding.sourceSnapshot, saved.origin.source)
            val changed = componentDocument("Demo", """{"demo":"demo-2","title":"运行示例"}""", demos = demos)
            store.transaction {
                put("views", view.ref, store.json.encodeToString(view.copy(document = changed)))
            }
            assertFailsWith<IllegalStateException> {
                engine.createNote("示例已换源", "question", sourceOrigin(binding), binding)
            }
            assertEquals(1, engine.snapshot().notes.size)
        }
    }

    @Test fun demoAndSourceComponentFreezeRequireRealComponentDemoAndViewSource() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val missingDemo = createView(engine, componentDocument("Demo", """{"demo":"missing"}"""))
            assertFailsWith<IllegalStateException> {
                engine.freezeSourceBinding(
                    missingDemo.ref, missingDemo.revision, "component", null, "source-1", "示例", JsonPayload.empty(),
                )
            }

            val validDemo = createView(engine, componentDocument(
                "Demo", """{"demo":"demo-1"}""", demos = mapOf("demo-1" to DemoResource("<pre>run()</pre>")),
            ))
            assertFailsWith<IllegalStateException> {
                engine.freezeSourceBinding(
                    validDemo.ref, validDemo.revision, "component", null, "not-in-view", "示例", JsonPayload.empty(),
                )
            }

            val sourceLink = createView(engine, componentDocument("SourceLink", """{"source":"source-1"}"""))
            assertFailsWith<IllegalStateException> {
                engine.freezeSourceBinding(
                    sourceLink.ref, sourceLink.revision, "component", null, "source-2", "源码", JsonPayload.empty(),
                )
            }
            assertTrue(engine.snapshot().notes.isEmpty())
        }
    }

    @Test fun graphSourceBindingAcceptsOnlyDirectOrOutgoingEdgeEvidence() {
        ViewStore(directory.resolve("edges.db")).use { store ->
            val engine = engine(store)
            val graph = graphWithEvidence("\"source-2\"")
            val view = createView(engine, graph)
            val frozenState = JsonPayload(Json.parseToJsonElement("""{"graph":"frozen"}""").jsonObject)
            val binding = freezeSourceNavigation(
                engine, view, "source-2", buildJsonObject {
                    put("elementId", "graph")
                    put("nodeId", "node-1")
                    put("nodeLabel", "起点")
                    put("revision", view.revision)
                    put("state", frozenState.value)
                },
                fallbackState = JsonPayload(Json.parseToJsonElement("""{"graph":"stale"}""").jsonObject),
            )
            assertEquals(frozenState, binding.state)
            val note = engine.createNote("关系为何成立？", "question", sourceOrigin(binding), binding)
            assertEquals(binding.sourceSnapshot, note.origin.source)

            assertFailsWith<IllegalStateException> {
                engine.freezeSourceBinding(
                    view.ref, view.revision, "graph", "node-2", "source-2", "终点", JsonPayload.empty(),
                )
            }

            val missingNodeId = createView(engine, graph)
            assertFailsWith<IllegalStateException> {
                freezeSourceNavigation(engine, missingNodeId, "source-1", sourceParams(missingNodeId, "graph"))
            }

            val staleView = createView(engine, graph)
            val staleBinding = engine.freezeSourceBinding(
                staleView.ref, staleView.revision, "graph", "node-1", "source-2", "起点", JsonPayload.empty(),
            )
            store.transaction {
                put("views", staleView.ref, store.json.encodeToString(staleView.copy(document = graphWithEvidence(""))))
            }
            assertFailsWith<IllegalStateException> {
                engine.createNote("关系证据已删除", "question", sourceOrigin(staleBinding), staleBinding)
            }
            assertEquals(1, engine.snapshot().notes.size)
        }
    }

    @Test fun boardEvidenceUsesGraphElementAndNodeEndpointIdentity() {
        ViewStore(directory.resolve("board-edges.db")).use { store ->
            val engine = engine(store)
            val document = ViewDocument(
                question = "跨图关系证据如何工作？",
                spec = JsonPayload(Json.parseToJsonElement(
                    """{"root":"board","elements":{
                      "board":{"type":"Board","props":{"edges":[
                        {"id":"call","source":{"elementId":"ga","nodeId":"same"},
                         "target":{"elementId":"gb","nodeId":"same"},"kind":"call","evidence":["source-2"]}
                      ]},"children":[]},
                      "ga":{"type":"Graph","props":{"nodes":[{"id":"same","label":"入口"}],"edges":[]},"children":[]},
                      "gb":{"type":"Graph","props":{"nodes":[{"id":"same","label":"缓存"}],"edges":[]},"children":[]}
                    }}""".trimIndent(),
                ).jsonObject),
                sources = sourceReferences(),
            )
            val view = createView(engine, document)
            assertTrue(engine.hasGraphNode(view.ref, view.revision, "ga", "same"))
            assertTrue(engine.hasGraphNode(view.ref, view.revision, "gb", "same"))
            val binding = freezeSourceNavigation(
                engine,
                view,
                "source-2",
                buildJsonObject {
                    put("elementId", "ga")
                    put("nodeId", "same")
                    put("nodeLabel", "入口")
                    put("revision", view.revision)
                    put("canPrevious", true)
                    put("canNext", false)
                },
                hostId = "host-toolwindow",
                hostKind = BrowserHostKind.TOOL_WINDOW,
            )
            assertEquals("host-toolwindow", binding.hostId)
            assertEquals(BrowserHostKind.TOOL_WINDOW, binding.hostKind)
            assertTrue(binding.canPrevious)
            assertFalse(binding.canNext)
            assertTrue(engine.isCurrentSourceBinding(binding))

            val unscopedBoardEdge = document.copy(spec = JsonPayload(Json.parseToJsonElement(
                """{"root":"board","elements":{
                  "board":{"type":"Board","props":{"edges":[
                    {"id":"call","source":"same","target":"same","kind":"call","evidence":["source-2"]}
                  ]},"children":[]},
                  "ga":{"type":"Graph","props":{"nodes":[{"id":"same","label":"入口"}],"edges":[]},"children":[]},
                  "gb":{"type":"Graph","props":{"nodes":[{"id":"same","label":"缓存"}],"edges":[]},"children":[]}
                }}""".trimIndent(),
            ).jsonObject))
            store.transaction {
                put("views", view.ref, store.json.encodeToString(view.copy(document = unscopedBoardEdge)))
            }
            assertFalse(engine.isCurrentSourceBinding(binding), "Board endpoint 必须带所属 Graph elementId")

            val removedEvidence = document.copy(spec = JsonPayload(Json.parseToJsonElement(
                """{"root":"board","elements":{
                  "board":{"type":"Board","props":{"edges":[
                    {"id":"call","source":{"elementId":"gb","nodeId":"same"},
                     "target":{"elementId":"ga","nodeId":"same"},"kind":"call","evidence":["source-2"]}
                  ]},"children":[]},
                  "ga":{"type":"Graph","props":{"nodes":[{"id":"same","label":"入口"}],"edges":[]},"children":[]},
                  "gb":{"type":"Graph","props":{"nodes":[{"id":"same","label":"缓存"}],"edges":[]},"children":[]}
                }}""".trimIndent(),
            ).jsonObject))
            store.transaction {
                put("views", view.ref, store.json.encodeToString(view.copy(document = removedEvidence)))
            }
            assertFalse(engine.isCurrentSourceBinding(binding))
        }
    }

    @Test fun noteDetailsRequireTheCurrentRevisionReference() {
        ViewStore(directory.resolve("note-details.db")).use { store ->
            val engine = engine(store)
            val note = engine.createNote("待补充的问题", "question", ViewOrigin())
            assertEquals(note, engine.noteDetails(note.ref))
            val edited = engine.editNote(note.ref, "更新后的问题")
            assertEquals(edited, engine.noteDetails(edited.ref))
            assertFailsWith<IllegalStateException> { engine.noteDetails(note.ref) }
        }
    }

    @Test fun noteEntryAndSelectionSnapshotSurviveStorage() {
        ViewStore(directory.resolve("note-origins.db")).use { store ->
            val engine = engine(store)
            val view = createSourceView(engine)
            val selectedSource = ResolvedSource("src/Example.kt", "fun target() {}", "target", "version", 1)
            val sourceNote = engine.createNote(
                "询问所选源码",
                "question",
                ViewOrigin(
                    viewRef = view.ref,
                    source = selectedSource,
                    entry = ViewOriginEntry.SOURCE_SELECTION,
                ),
            )
            val nodeNote = engine.createNote(
                "询问图节点",
                "question",
                ViewOrigin(
                    viewRef = view.ref,
                    elementId = "graph",
                    nodeId = "node-1",
                    nodeLabel = "目标节点",
                    entry = ViewOriginEntry.NODE,
                ),
            )
            assertEquals(ViewOriginEntry.SOURCE_SELECTION, engine.noteDetails(sourceNote.ref).origin.entry)
            assertEquals(selectedSource, engine.noteDetails(sourceNote.ref).origin.source)
            assertEquals(ViewOriginEntry.NODE, engine.noteDetails(nodeNote.ref).origin.entry)
            assertNull(engine.noteDetails(nodeNote.ref).origin.source)
        }
    }
    @Test fun staleQuestionContextsAreRejected() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val note = engine.createNote("问题", "question", ViewOrigin())
            val cancelled = engine.context(note.ref, ViewOrigin())
            engine.deleteNote(note.ref)
            assertFailsWith<IllegalStateException> { engine.present(cancelled.context, document(note.text)) }
            val editable = engine.createNote("原文", "question", ViewOrigin())
            val old = engine.context(editable.ref, ViewOrigin())
            val edited = engine.editNote(editable.ref, "修改")
            assertFailsWith<IllegalStateException> { engine.present(old.context, document(editable.text)) }
            val one = engine.context(edited.ref, ViewOrigin())
            val two = engine.context(edited.ref, ViewOrigin())
            engine.present(one.context, document(edited.text))
            assertFailsWith<IllegalStateException> { engine.present(two.context, document(edited.text)) }
            assertEquals(1, engine.snapshot().views.size)
        }
    }
    @Test fun feedbackReadAndAcknowledgement() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val a = engine.createNote("连线遮挡", "feedback", ViewOrigin())
            val b = engine.createNote("字太小", "feedback", ViewOrigin())
            assertEquals(2, engine.context(null, ViewOrigin()).pending.size)
            val edited = engine.editNote(b.ref, "字号与缩放不一致")
            assertFailsWith<IllegalStateException> { engine.finishFeedback(listOf(a.ref, b.ref)) }
            assertTrue(engine.snapshot().notes.all { it.status == "pending" })
            assertEquals(listOf(a.ref, edited.ref), engine.finishFeedback(listOf(a.ref, edited.ref)))
            assertEquals(listOf(a.ref), engine.finishFeedback(listOf(a.ref)))
            val question = engine.createNote("是什么？", "question", ViewOrigin())
            assertFailsWith<IllegalArgumentException> { engine.finishFeedback(listOf(question.ref)) }
        }
    }
    @Test fun revisionConflictAndHierarchy() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val root = engine.present(engine.context(null, ViewOrigin()).context, document())
            val frozen = engine.sourceRef(ViewOrigin(viewRef = root.ref, elementId = "json"))
            val child = engine.present(engine.context(frozen, ViewOrigin()).context, document("新的疑问"))
            engine.move(child.ref, null)
            assertNull(engine.snapshot().views.last().parentRef)
            assertEquals(root.ref, engine.snapshot().views.last().origin.viewRef)
            val a = engine.context(root.ref, ViewOrigin())
            val b = engine.context(root.ref, ViewOrigin())
            engine.present(a.context, document(title = "说明"))
            assertFailsWith<IllegalStateException> { engine.present(b.context, document(title = "冲突")) }
            assertEquals("说明", engine.snapshot().views.first().document.title)
            engine.move(child.ref, root.ref)
            assertFailsWith<IllegalArgumentException> { engine.move(root.ref, child.ref) }
            engine.delete(root.ref)
            assertTrue(engine.snapshot().views.isEmpty())
        }
    }
    @Test fun concurrentRetries() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val ticket = engine.context(null, ViewOrigin())
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures = (1..8).map { pool.submit<PresentReceipt> { engine.present(ticket.context, document()) } }
                assertEquals(1, futures.map { it.get() }.distinct().size)
                assertEquals(1, engine.snapshot().views.size)
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun feedbackEditOrCompletionInvalidatesWriteTicket() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val root = engine.present(engine.context(null, ViewOrigin()).context, document())
            val feedback = engine.createNote("改善间距", "feedback", ViewOrigin(viewRef = root.ref))
            val ticket = engine.context(feedback.ref, ViewOrigin())
            val edited = engine.editNote(feedback.ref, "改善字号")
            assertFailsWith<IllegalStateException> { engine.present(ticket.context, document()) }
            val fresh = engine.context(edited.ref, ViewOrigin())
            engine.present(fresh.context, document(title = "改进"))
            assertEquals("pending", engine.snapshot().notes.single().status)
            engine.finishFeedback(listOf(edited.ref))
            assertFailsWith<IllegalStateException> { engine.present(engine.context(edited.ref, ViewOrigin()).context, document(title = "过期修订")) }
        }
    }
    @Test fun noRefAlwaysCreatesIndependentResult() {
        ViewStore(directory.resolve("views.db")).use { store ->
            val engine = engine(store)
            val root = engine.present(engine.context(null, ViewOrigin()).context, document())
            val independent = engine.present(engine.context(null, ViewOrigin(viewRef = root.ref)).context, document("独立问题"))
            val saved = engine.snapshot().views.first { it.ref == independent.ref }
            assertNull(saved.parentRef)
            assertEquals(root.ref, saved.origin.viewRef)
        }
    }
}
