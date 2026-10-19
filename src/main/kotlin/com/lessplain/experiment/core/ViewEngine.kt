package com.lessplain.experiment.core

import com.lessplain.experiment.model.*
import com.lessplain.experiment.storage.ViewStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** 结果身份和任务事务的唯一入口。任何浏览器状态都不能改变已捕获的提交目标。 */
class ViewEngine(
    private val store: ViewStore,
    private val catalog: () -> JsonPayload,
    private val validateSpec: (ViewDocument) -> Unit,
    private val validateSelection: ((ResolvedSource) -> ResolvedSource)? = null,
    private val resolveSources: (Map<String, SourceReference>) -> Map<String, ResolvedSource>,
) {
    private val json = store.json
    private val componentSnapshots = java.util.concurrent.ConcurrentHashMap<String, SampleLocation>()

    init {
        NoteHistory.upgrade(store)
    }

    /** 在导航发生时读取并冻结 sourceId、组件/图节点身份和画布状态。 */
    fun freezeSourceBinding(
        viewRef: String,
        revision: Int,
        elementId: String?,
        nodeId: String?,
        sourceId: String,
        label: String,
        state: JsonPayload,
    ): EditorSourceBinding = store.read {
        val view = record(viewRef)
        check(view.revision == revision) { "页面已变化，请重新定位源码" }
        val source = view.sources[sourceId] ?: error("源码引用不存在")
        check(view.document.sources.orEmpty().containsKey(sourceId)) { "源码引用已变化，请重新定位" }
        if (elementId == null) {
            check(nodeId == null) { "源码节点身份不完整，请重新定位" }
            EditorSourceBinding(viewRef, revision, null, null, sourceId, label, state, source)
        } else if (nodeId != null) {
            requireGraphNode(view, elementId, nodeId, sourceId, label)
            EditorSourceBinding(viewRef, revision, elementId, nodeId, sourceId, label, state, source)
        } else {
            val element = requireSourceElement(view, elementId, sourceId)
            EditorSourceBinding(
                viewRef, revision, elementId, null, sourceId, label, state, source,
                componentType = element.type, demoId = element.demoId,
            )
        }
    }

    fun context(ref: String?, origin: ViewOrigin): ContextResult = store.transaction {
        var view: ViewRecord? = null
        var note: ViewNote? = null
        var captured = origin
        var parent: String? = null
        if (!ref.isNullOrBlank()) {
            when {
                ref.startsWith("view:") -> view = record(ref)
                ref.startsWith("note:") -> {
                    note = note(ref)
                    check(note.status != "cancelled") { "问题已取消" }
                    captured = note.origin
                    parent = note.ownerRef
                    if (note.kind == "feedback") view = note.ownerRef?.let { record(it) }
                    else view = note.answerRef?.let { record(it) }
                }
                ref.startsWith("source:") -> {
                    val saved = get("origins", ref) ?: error("来源引用不存在")
                    captured = json.decodeFromString(saved)
                    parent = captured.viewRef
                }
                else -> error("引用无效，请使用插件提供的 ref")
            }
        }
        if (view != null && note == null) captured = view.origin
        val ticket = ContextTicket(
            context = newRef("context"), origin = captured, targetRef = view?.ref,
            expectedRevision = view?.revision, parentRef = view?.parentRef ?: parent,
            noteRef = note?.ref, noteRevision = note?.revision,
            question = note?.takeIf { it.kind == "question" }?.text ?: view?.document?.question,
        )
        put("contexts", ticket.context, json.encodeToString(ticket))
        val scope = view?.ref ?: parent ?: origin.viewRef
        val originView = captured.viewRef?.let { get("views", it)?.let { saved -> json.decodeFromString<ViewRecord>(saved) } }
        ContextResult(
            context = ticket.context, target = ContextTarget(if (view == null) "create" else "update", view?.ref, ticket.parentRef),
            origin = captured, catalog = catalog(), view = view, originView = originView,
            pending = pending(scope), question = ticket.question,
        )
    }

    fun present(context: String, document: ViewDocument): PresentReceipt {
        val normalized = document.copy(
            question = document.question.trim(),
            title = document.title?.trim()?.takeIf { it.isNotEmpty() },
            sources = document.sources.orEmpty(), demos = document.demos.orEmpty(),
            codeSamples = document.codeSamples.orEmpty().mapValues { (_, sample) ->
                sample.copy(code = sample.code.replace("\r\n", "\n").replace('\r', '\n'))
            },
        )
        require(normalized.question.isNotEmpty()) { "原问题不能为空" }
        val encoded = json.encodeToJsonElement(normalized).jsonObject
        // 新增可选资源不能使旧版无样例页面的网络重试收据失效。
        val hash = digest(canonical(if (normalized.codeSamples.isNullOrEmpty()) JsonObject(encoded - "codeSamples") else encoded).toString())
        val retry = store.read { get("commits", context)?.let { committed(it, hash) } }
        if (retry != null) return retry
        validateSpec(normalized)
        val sources = resolveSources(normalized.sources.orEmpty())
        return store.transaction {
            get("commits", context)?.let { return@transaction committed(it, hash) }
            val ticket = get("contexts", context)?.let { json.decodeFromString<ContextTicket>(it) }
                ?: error("上下文不存在，请重新读取")
            require(ticket.question == null || ticket.question == normalized.question) { "原问题与固定上下文不一致" }
            val (previous, task) = presentState(ticket)
            val now = System.currentTimeMillis()
            val saved = ViewRecord(
                ref = previous?.ref ?: newRef("view"), revision = (previous?.revision ?: 0) + 1,
                document = normalized, sources = sources,
                createdAt = previous?.createdAt ?: now, updatedAt = now,
                parentRef = previous?.parentRef ?: ticket.parentRef, origin = previous?.origin ?: ticket.origin,
                answeredNote = task?.takeIf { it.kind == "question" }?.copy(status = "answered") ?: previous?.answeredNote,
            )
            put("views", saved.ref, json.encodeToString(saved))
            if (task?.kind == "question") {
                remove("notes", baseRef(task.ref))
                NoteHistory.removeUncommittedContexts(this, store, task.ref)
            }
            val receipt = PresentReceipt(saved.ref, saved.revision)
            put("commits", context, json.encodeToString(Committed(hash, receipt)))
            receipt
        }
    }

    fun finishFeedback(refs: List<String>): List<String> = store.transaction {
        require(refs.isNotEmpty()) { "反馈引用不能为空" }
        val notes = refs.distinct().map { note(it).also { note ->
            require(note.kind == "feedback" && note.status in setOf("pending", "finished")) { "只能完成未取消的缺陷反馈，源码问题须提交答案" }
        } }
        notes.forEach { put("notes", baseRef(it.ref), json.encodeToString(it.copy(status = "finished"))) }
        refs.distinct()
    }

    fun snapshot(): AppSnapshot = store.read {
        AppSnapshot(
            views = all("views").values.map { json.decodeFromString<ViewRecord>(it) }.sortedBy { it.createdAt },
            notes = all("notes").values.map { json.decodeFromString<ViewNote>(it) }.sortedBy { it.createdAt },
            browse = all("browse").mapValues { JsonPayload(json.parseToJsonElement(it.value).jsonObject) },
        )
    }

    /** 使用精确的 revision ref 读取单条备注，拒绝弹层打开期间已编辑的旧引用。 */
    fun noteDetails(ref: String): ViewNote = store.read {
        note(ref)
    }

    /** 验证 bridge 返回的画布节点仍属于指定 revision。 */
    fun hasGraphNode(viewRef: String, revision: Int, elementId: String, nodeId: String): Boolean = store.read {
        runCatching {
            val view = record(viewRef)
            check(view.revision == revision)
            val element = view.document.spec.value["elements"]?.jsonObject?.get(elementId)?.jsonObject
                ?: error("页面图节点已变化")
            check(element["type"]?.jsonPrimitive?.contentOrNull == "Graph")
            val nodes = element["props"]?.jsonObject?.get("nodes")?.jsonArray.orEmpty()
            nodes.count { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == nodeId } == 1
        }.getOrDefault(false)
    }

    /** Inlay 步进前再次验证来源边和 revision，阻止过期 host 发送导航。 */
    fun isCurrentSourceBinding(binding: EditorSourceBinding): Boolean = store.read {
        runCatching {
            val view = record(binding.viewRef)
            check(view.revision == binding.revision)
            check(view.sources[binding.sourceId] == binding.sourceSnapshot)
            val elementId = binding.elementId ?: error("源码 binding 没有关联图节点")
            val nodeId = binding.nodeId ?: error("源码 binding 没有关联图节点")
            requireGraphNode(view, elementId, nodeId, binding.sourceId, binding.label)
        }.isSuccess
    }

    fun createNote(
        text: String,
        kind: String,
        origin: ViewOrigin,
        binding: EditorSourceBinding? = null,
    ): ViewNote = store.transaction {
        require(kind in setOf("question", "feedback")) { "问题类型无效" }
        require(text.isNotBlank()) { "内容不能为空" }
        validateOriginEntry(origin)
        if (binding != null) validateEditorBinding(origin, binding)
        else {
            origin.viewRef?.let { record(it) }
            origin.source?.let(::validateSelectionSource)
            origin.sample?.let { validateSampleOrigin(record(requireNotNull(origin.viewRef)), origin) }
            origin.componentSnapshot?.let { validateComponentOrigin(record(requireNotNull(origin.viewRef)), origin, componentSnapshots[it.snapshot.id]) }
        }
        val note = ViewNote(newRef("note") + "@1", kind, text.trim(), origin, createdAt = System.currentTimeMillis(), ownerRef = origin.viewRef)
        put("notes", baseRef(note.ref), json.encodeToString(note))
        note
    }

    fun editNote(ref: String, text: String): ViewNote = store.transaction {
        val previous = note(ref)
        require(previous.status == "pending" && text.isNotBlank()) { "只能编辑未完成的问题" }
        val edited = previous.copy(text = text.trim(), revision = previous.revision + 1, ref = baseRef(previous.ref) + "@" + (previous.revision + 1))
        put("notes", baseRef(ref), json.encodeToString(edited))
        edited
    }

    /** 仅硬删除精确版本的待答行及未提交凭据，不删除已形成的回答历史。 */
    fun deleteNote(ref: String) = store.transaction {
        val previous = note(ref)
        require(previous.status == "pending") { "问题已经处理" }
        remove("notes", baseRef(ref))
        NoteHistory.removeUncommittedContexts(this, store, ref)
    }

    fun sourceRef(origin: ViewOrigin): String = store.transaction {
        origin.viewRef?.let { record(it) }
        origin.sample?.let { validateSampleOrigin(record(requireNotNull(origin.viewRef)), origin) }
        origin.componentSnapshot?.let { validateComponentOrigin(record(requireNotNull(origin.viewRef)), origin, componentSnapshots[it.snapshot.id]) }
        newRef("source").also { put("origins", it, json.encodeToString(origin)) }
    }

    /** 样例导航只接受当前页面及其实际组件/节点声明的引用。 */
    fun sampleLocation(ref: String, revision: Int, target: CodeSampleTarget, elementId: String?, nodeId: String?, state: JsonPayload): SampleLocation = store.read {
        val view = record(ref)
        check(view.revision == revision) { "页面已变化，请重新定位教学代码" }
        sampleLocation(view, target, elementId, nodeId, state)
    }

    fun saveBrowse(ref: String, state: JsonPayload) = store.transaction {
        record(ref)
        put("browse", ref, state.value.toString())
    }

    /** 快照签发与版本检查同一次读取完成；未保存的预览只占用有界内存。 */
    fun componentLocation(ref: String, revision: Int, elementId: String, value: JsonElement, state: JsonPayload): SampleLocation = store.read {
        val view = record(ref)
        check(view.revision == revision) { "页面已变化，请重新打开代码" }
        val next = componentLocation(view, elementId, value, state)
        componentSnapshots.values.firstOrNull { it.viewRef == ref && it.origin == next.origin && it.sample == next.sample &&
            it.componentSnapshot?.componentVersion == next.componentSnapshot?.componentVersion } ?: run {
            require(componentSnapshots.size < 100) { "打开的组件快照过多，请先关闭部分代码标签" }
            next.also { componentSnapshots[it.target.id] = it }
        }
    }

    fun releaseComponentSnapshot(id: String) { componentSnapshots.remove(id) }
    fun clearComponentSnapshots() { componentSnapshots.clear() }

    fun rename(ref: String, title: String) = store.transaction {
        require(title.isNotBlank()) { "标题不能为空" }
        val view = record(ref)
        put("views", ref, json.encodeToString(view.copy(revision = view.revision + 1, document = view.document.copy(title = title.trim()), updatedAt = System.currentTimeMillis())))
    }

    fun move(ref: String, parentRef: String?) = store.transaction {
        val view = record(ref)
        var cursor = parentRef
        while (cursor != null) {
            require(cursor != ref) { "不能将页面移动到自身后代" }
            cursor = record(cursor).parentRef
        }
        put("views", ref, json.encodeToString(view.copy(parentRef = parentRef, revision = view.revision + 1)))
    }

    fun delete(ref: String) = store.transaction {
        record(ref)
        val descendants = descendantRefs(ref)
        descendants.forEach { remove("views", it); remove("browse", it) }
        all("notes").forEach { (key, value) ->
            val note = json.decodeFromString<ViewNote>(value)
            if (note.ownerRef in descendants) remove("notes", key)
        }
        // 收据可绕过 ticket 直接返回结果；删除时必须同时撤销，旧请求不能复活已删页面。
        val liveViews = all("views").keys
        (all("contexts").keys + all("commits").keys).forEach { key ->
            val ticket = get("contexts", key)?.let { json.decodeFromString<ContextTicket>(it) }
            val receipt = get("commits", key)?.let { json.decodeFromString<Committed>(it).receipt }
            val invalid = if (receipt != null) receipt.ref !in liveViews
                else ticket != null && !canPresent(ticket)
            if (invalid) {
                remove("contexts", key)
                remove("commits", key)
            }
        }
        all("origins").forEach { (key, value) ->
            if (json.decodeFromString<ViewOrigin>(value).viewRef in descendants) remove("origins", key)
        }
    }

    /** 与提交前置条件保持一致；有效独立任务的冻结来源允许引用刚删除的页面。 */
    private fun ViewStore.Transaction.canPresent(ticket: ContextTicket): Boolean = runCatching { presentState(ticket) }.isSuccess

    private fun ViewStore.Transaction.presentState(ticket: ContextTicket): Pair<ViewRecord?, ViewNote?> {
        val previous = ticket.targetRef?.let { record(it) }
        check(previous?.revision == ticket.expectedRevision) { "页面已变化，请使用 ref 重新读取上下文" }
        val task = ticket.noteRef?.let { note(it) }
        check(task == null || (task.revision == ticket.noteRevision && task.status != "cancelled")) { "问题已变化，请重新读取" }
        check(task?.kind != "feedback" || (task.status == "pending" && ticket.targetRef != null)) { "反馈已处理或没有可修订页面" }
        check(task == null || task.kind == "feedback" || task.answerRef == ticket.targetRef) { "问题已经由另一个提交回答，请重新读取" }
        ticket.parentRef?.let { record(it) }
        return previous to task
    }

    private fun ViewStore.Transaction.pending(scope: String?): List<ViewNote> {
        val refs = scope?.let { descendantRefs(it) }
        return all("notes").values.map { json.decodeFromString<ViewNote>(it) }
            .filter { it.status == "pending" && (refs == null || it.ownerRef in refs) }.sortedBy { it.createdAt }
    }

    private fun ViewStore.Transaction.descendantRefs(root: String): Set<String> {
        val records = all("views").values.map { json.decodeFromString<ViewRecord>(it) }
        val found = mutableSetOf(root)
        do { val before = found.size; records.filter { it.parentRef in found }.forEach { found += it.ref } } while (found.size > before)
        return found
    }

    private fun ViewStore.Transaction.record(ref: String): ViewRecord =
        get("views", ref)?.let { json.decodeFromString(it) } ?: error("页面不存在：" + ref)

    private fun ViewStore.Transaction.validateEditorBinding(origin: ViewOrigin, binding: EditorSourceBinding) {
        val view = record(binding.viewRef)
        check(view.revision == binding.revision) { "页面或源码节点已变化；输入和选区已保留，请重新定位" }
        check(view.sources[binding.sourceId] == binding.sourceSnapshot &&
            view.document.sources.orEmpty().containsKey(binding.sourceId)
        ) { "源码引用已变化；输入和选区已保留，请重新定位" }
        check(origin.viewRef == binding.viewRef &&
            origin.elementId == binding.elementId &&
            origin.nodeId == binding.nodeId &&
            origin.nodeLabel == binding.label.takeIf { binding.nodeId != null } &&
            origin.state == binding.state
        ) { "问题来源已变化；输入和选区已保留，请重新打开提问" }
        if (binding.elementId != null && binding.nodeId != null) {
            check(binding.componentType == null && binding.demoId == null) { "源码节点身份已变化；输入和选区已保留，请重新定位" }
            requireGraphNode(view, binding.elementId, binding.nodeId, binding.sourceId, binding.label)
        } else if (binding.elementId != null) {
            val element = requireSourceElement(view, binding.elementId, binding.sourceId)
            check(element.type == binding.componentType && element.demoId == binding.demoId) {
                "源码组件或示例已变化；输入和选区已保留，请重新定位"
            }
        } else {
            check(binding.nodeId == null && binding.componentType == null && binding.demoId == null &&
                origin.elementId == null && origin.nodeId == null
            ) { "源码节点身份已变化；输入和选区已保留，请重新定位" }
        }

        val selected = origin.source ?: error("没有冻结的选区源码；输入已保留")
        validateSelectionSource(selected)
        check(
            selected.path == binding.sourceSnapshot.path &&
            selected.version == binding.sourceSnapshot.version
        ) { "所选源码已变化；输入和选区已保留，请重新选择" }
    }

    private fun ViewStore.Transaction.validateOriginEntry(origin: ViewOrigin) {
        require(origin.componentSnapshot == null || origin.entry == ViewOriginEntry.COMPONENT_SELECTION) { "组件快照入口无效" }
        when (origin.entry) {
            ViewOriginEntry.NODE -> require(
                origin.viewRef != null && origin.elementId != null && origin.nodeId != null && origin.source == null && origin.sample == null,
            ) { "节点备注必须绑定图节点且不能包含源码选区" }
            ViewOriginEntry.COMPONENT -> require(
                origin.viewRef != null && origin.elementId != null && origin.nodeId == null && origin.source == null && origin.sample == null,
            ) { "组件备注必须绑定组件且不能包含源码选区" }
            ViewOriginEntry.SOURCE_SELECTION -> require(origin.source != null && origin.sample == null) { "源码选择备注缺少冻结源码" }
            ViewOriginEntry.SAMPLE_SELECTION -> require(origin.viewRef != null && origin.sample != null && origin.source == null) { "教学代码问题缺少冻结样例" }
            ViewOriginEntry.COMPONENT_SELECTION -> require(origin.viewRef != null && origin.elementId != null && origin.nodeId == null && origin.source == null && origin.sample == null && origin.componentSnapshot != null) { "组件代码问题缺少冻结快照" }
            null -> Unit
            else -> error("备注入口类型无效")
        }
    }

    private fun validateSelectionSource(selected: ResolvedSource): ResolvedSource {
        validateSelection?.let { validate ->
            val verified = validate(selected)
            check(verified == selected) { "所选源码已变化；输入和选区已保留，请重新选择" }
            return verified
        }
        check(selected.selectionRange == null) { "当前宿主未提供精确选区校验；输入已保留" }
        val verified = resolveSources(
            mapOf("selection" to SourceReference(selected.path, selected.text, selected.symbol)),
        )["selection"] ?: error("所选源码无法在当前文档中确认；输入已保留")
        check(verified == selected) { "所选源码已变化；输入和选区已保留，请重新选择" }
        return verified
    }

    private fun requireGraphNode(view: ViewRecord, elementId: String, nodeId: String, sourceId: String, label: String) {
        val elements = view.document.spec.value["elements"]?.jsonObject ?: error("页面图节点已变化，请重新定位")
        val element = elements[elementId]?.jsonObject ?: error("页面图节点已删除，请重新定位")
        check(element["type"]?.jsonPrimitive?.contentOrNull == "Graph") { "页面图节点身份已变化，请重新定位" }
        val props = element["props"]?.jsonObject ?: error("页面图节点已变化，请重新定位")
        val nodes = props["nodes"]?.jsonArray.orEmpty()
        val matches = nodes.mapNotNull { it as? JsonObject }
            .filter { it["id"]?.jsonPrimitive?.contentOrNull == nodeId }
        check(matches.size == 1 && matches.single()["label"]?.jsonPrimitive?.contentOrNull == label) {
            "源码节点已删除或变化，请重新定位"
        }
        val directSource = matches.single()["source"]?.jsonPrimitive?.contentOrNull == sourceId
        val localOutgoingEvidence = hasOutgoingEvidence(props, elementId, nodeId, sourceId)
        val boardOutgoingEvidence = elements.values.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "Board" }
            .mapNotNull { it["props"]?.jsonObject }
            .any { hasOutgoingEvidence(it, elementId, nodeId, sourceId, requireScopedEndpoint = true) }
        check(directSource || localOutgoingEvidence || boardOutgoingEvidence) {
            "源码不属于该图节点或其关系，请重新定位"
        }
    }

    private fun hasOutgoingEvidence(
        props: JsonObject,
        elementId: String,
        nodeId: String,
        sourceId: String,
        requireScopedEndpoint: Boolean = false,
    ): Boolean =
        props["edges"]?.jsonArray.orEmpty().any { edgeValue ->
            val edge = edgeValue as? JsonObject ?: return@any false
            val source = edge["source"] ?: return@any false
            (!requireScopedEndpoint || source is JsonObject) && endpointMatches(source, elementId, nodeId) &&
                edge["evidence"]?.jsonArray.orEmpty()
                .any { (it as? JsonPrimitive)?.contentOrNull == sourceId }
        }

    private fun endpointMatches(value: JsonElement, elementId: String, nodeId: String): Boolean = when (value) {
        is JsonPrimitive -> value.contentOrNull == nodeId
        is JsonObject -> value["elementId"]?.jsonPrimitive?.contentOrNull == elementId &&
            value["nodeId"]?.jsonPrimitive?.contentOrNull == nodeId
        else -> false
    }

    /** 组件跳转以实际渲染的 source/demo 属性为准，不借用当前图节点来源。 */
    private data class SourceElementIdentity(val type: String, val demoId: String? = null)

    private fun requireSourceElement(view: ViewRecord, elementId: String, sourceId: String): SourceElementIdentity {
        val elements = view.document.spec.value["elements"]?.jsonObject ?: error("页面组件已变化，请重新定位")
        val element = elements[elementId]?.jsonObject ?: error("页面组件已删除，请重新定位")
        val props = element["props"]?.jsonObject ?: error("页面组件来源已变化，请重新定位")
        return when (element["type"]?.jsonPrimitive?.contentOrNull) {
            "SourceLink", "CodeExcerpt" -> {
                val type = element["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                check(props["source"]?.jsonPrimitive?.contentOrNull == sourceId) {
                    "组件源码引用已变化，请重新定位"
                }
                SourceElementIdentity(type)
            }
            "Demo" -> {
                val demoId = props["demo"]?.jsonPrimitive?.contentOrNull
                    ?: error("示例组件未引用有效示例，请重新定位")
                check(demoId.isNotBlank() && view.document.demos?.get(demoId) != null) {
                    "示例组件引用的示例不存在，请重新定位"
                }
                SourceElementIdentity("Demo", demoId)
            }
            else -> error("组件不支持源码定位，请重新定位")
        }
    }

    private fun ViewStore.Transaction.note(ref: String): ViewNote {
        val note = NoteHistory.find(this, store, ref) ?: error("问题不存在或已删除")
        check(note.ref == ref) { "反馈或问题已编辑，请重新读取引用" }
        return note
    }

    private fun ViewStore.Transaction.committed(value: String, hash: String): PresentReceipt {
        val saved = json.decodeFromString<Committed>(value)
        check(get("views", saved.receipt.ref) != null) { "结果已删除，请重新读取上下文" }
        check(saved.hash == hash) { "上下文已用于其他内容，请使用 ref 重新读取" }
        return saved.receipt
    }

    @Serializable
    private data class Committed(val hash: String, val receipt: PresentReceipt)
}

internal fun newRef(kind: String): String = kind + ":" + UUID.randomUUID()
internal fun baseRef(ref: String): String = ref.substringBefore('@')
internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** 对象键规范化用于网络重试去重；数组顺序仍保留用户的阅读顺序。 */
internal fun canonical(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
    is JsonArray -> JsonArray(value.map(::canonical))
    else -> value
}
