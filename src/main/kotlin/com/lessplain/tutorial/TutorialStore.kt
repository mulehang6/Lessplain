package com.lessplain.tutorial

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 内容与重试回执使用同一快照，阅读状态独立保存；单线程原子替换文件，关闭时排空写入。 */
class TutorialStore(private val root: Path, private val cleanupDeleted: (Path, String) -> Unit = ::cleanTutorialDirectory,
    private val cleanupFailed: (String, Throwable) -> Unit = { _, _ -> }) : AutoCloseable {
    private val gson = Gson()
    private val lifetimes = TutorialLifetimes(root)
    private val deleting = mutableSetOf<String>()
    private val writer = TutorialWriteQueue()
    private val records = linkedMapOf<String, TutorialRecord>()
    private val resources = linkedMapOf<String, TutorialResourceState>()
    private val annotations = linkedMapOf<String, TutorialAnnotation>()
    private val contexts = linkedMapOf<String, TutorialContext>()
    private val sources = linkedMapOf<String, TutorialSource>()
    private val reading = linkedMapOf<String, TutorialReadingState>()
    private val receipts = linkedMapOf<String, TutorialRecord>()
    private val toolReceipts = linkedMapOf<String, String>()
    private val removedContexts = linkedMapOf<String, Set<String>>()
    private val lockChannel: FileChannel
    private val writerLock: java.nio.channels.FileLock?
    val readOnly: Boolean
    private var closed = false
    val writeFailure: Throwable? get() = writer.failure
    private var editing: String? = null

    init {
        Files.createDirectories(root)
        lockChannel = FileChannel.open(root.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        writerLock = try { lockChannel.tryLock() } catch (_: OverlappingFileLockException) { null }
        readOnly = writerLock == null
        try {
            Files.list(root).use { paths -> paths.filter { Files.isDirectory(it) && !lifetimes.deleted(it.fileName.toString()) }.forEach { path ->
                val content = read(path.resolve("content.json"), TutorialContentState::class.java)
                val record = content?.record ?: read(path.resolve("document.json"), TutorialRecord::class.java)
                record?.let { records[it.id] = it }
                (content?.resources ?: read(path.resolve("resources.json"), TutorialResourceState::class.java))?.let { resources[path.fileName.toString()] = it }
                (content?.annotations ?: read(path.resolve("annotations.json"), Array<TutorialAnnotation>::class.java)?.toList()).orEmpty().forEach { annotations[it.id] = it }
                content?.receipts?.let(receipts::putAll)
                content?.toolReceipts?.let(toolReceipts::putAll)
                content?.removedContexts?.let { removedContexts[path.fileName.toString()] = it }
                read(path.resolve("reading.json"), TutorialReadingState::class.java)?.let { reading[path.fileName.toString()] = it }
            } }
            read(root.resolve("contexts.json"), Array<TutorialContext>::class.java)?.forEach { contexts[it.id] = it }
            read(root.resolve("sources.json"), Array<TutorialSource>::class.java)?.forEach { sources[it.id] = it }
            read(root.resolve("receipts.json"), Array<TutorialEditReceipt>::class.java)?.forEach { receipts.putIfAbsent(it.key, it.record) }
            val storedToolReceipts = read(root.resolve("tool-receipts.json"), Array<TutorialToolReceipt>::class.java).orEmpty()
            storedToolReceipts.forEach {
                lifetimes.bootstrap(it.key, it.reply)
                if (!lifetimes.legacyResource(it.key, it.reply) && annotationReceiptId(it.reply)?.let(annotations::containsKey) != false) toolReceipts.putIfAbsent(it.key, lifetimes.sanitize(it.key, it.reply))
            }
            receipts.entries.removeIf { lifetimes.deleted(it.value.id) }
            records.values.forEach { lifetimes.rememberCreation(it.id, lifetimes.creationKey(it.contextId, it.title, it.markdown, resource(it.id))) }
            val retainedContexts = records.values.map { it.contextId }.toSet() + annotations.values.mapNotNull { it.contextId }
            val pruned = contexts.keys.removeAll((lifetimes.deletedContexts() + removedContexts.values.flatten()) - retainedContexts)
            if (!readOnly) {
                if (pruned) persist(root.resolve("contexts.json"), contexts.values.toList())
                if (lifetimes.deletedIds().isNotEmpty() || storedToolReceipts.associate { it.key to it.reply } != toolReceipts) {
                    toolReceipts.replaceAll { key, reply -> lifetimes.sanitize(key, reply) }
                    persist(root.resolve("receipts.json"), receipts.map { TutorialEditReceipt(it.key, it.value) })
                    persist(root.resolve("tool-receipts.json"), toolReceipts.map { TutorialToolReceipt(it.key, it.value) })
                }
                writer.submit { lifetimes.deletedIds().forEach { id -> runCatching { cleanupDeleted(root, id) }.onFailure { cleanupFailed(id, it) } } }
            }
        } catch (failure: Throwable) {
            writer.shutdown(); writerLock?.release(); lockChannel.close()
            throw failure
        }
    }

    private fun writable() {
        tutorialRequire(!closed, "CLOSED", "", "教程存储已关闭")
        tutorialRequire(!readOnly, "READ_ONLY", "", "另一个 IDE 实例正在保存此项目的教程，当前实例只读")
    }

    private fun <T> read(path: Path, type: Class<T>): T? = if (Files.exists(path)) gson.fromJson(Files.readString(path), type) else null
    private fun persist(path: Path, value: Any) {
        val text = gson.toJson(value)
        writer.write(path) { writeTutorialJson(path, text) }
    }

    @Synchronized fun list() = records.values.filterNot { isUnavailable(it.id) }
    @Synchronized fun isUnavailable(id: String) = id in deleting || lifetimes.deleted(id)
    fun isDeleted(id: String) = lifetimes.deleted(id)
    private fun editable(id: String) { record(id); tutorialRequire(id !in deleting, "DELETING", "id", "教程正在删除") }
    private fun available(id: String) { tutorialRequire(!lifetimes.deleted(id), "DELETED", "id", "教程已删除：$id") }
    @Synchronized fun record(id: String): TutorialRecord = run { available(id); records[id] ?: throw TutorialProblem("NOT_FOUND", "id", "教程不存在：$id") }
    @Synchronized fun resource(id: String): TutorialResourceState { available(id); return resources[id] ?: TutorialResourceState() }
    @Synchronized fun context(id: String) = contexts[id] ?: throw TutorialProblem("NOT_FOUND", "contextId", "冻结上下文不存在：$id")
    @Synchronized fun source(id: String) = sources[id] ?: throw TutorialProblem("NOT_FOUND", "sourceId", "源码引用不存在：$id")
    @Synchronized fun sources() = sources.values.toList()
    @Synchronized fun annotations(id: String? = null) = annotations.values.filter { id == null || it.materialId == id }
    @Synchronized fun annotation(id: String): TutorialAnnotation { lifetimes.deletedAnnotation(id)?.let(::available); return annotations[id] ?: throw TutorialProblem("NOT_FOUND", "id", "批注不存在：$id") }
    @Synchronized fun reading(id: String) = reading[id] ?: TutorialReadingState()
    @Synchronized fun receipt(key: String) = receipts[key]?.also { available(it.id) }
    @Synchronized fun toolReceipt(key: String): String? {
        tutorialRequire(lifetimes.requestOwner(key) !in deleting, "DELETING", "id", "教程正在删除")
        return lifetimes.deletedRequest(key)?.let(lifetimes::deletedReply) ?: toolReceipts[key]?.let { lifetimes.sanitize(key, it) }
    }
    @Synchronized fun saveToolReceipt(key: String, reply: String): String {
        writable()
        tutorialRequire(lifetimes.requestOwner(key) !in deleting, "DELETING", "id", "教程正在删除")
        val annotationId = annotationReceiptId(reply)
        tutorialRequire(annotationId == null || annotationId in annotations || lifetimes.deletedAnnotation(annotationId) != null,
            "NOT_FOUND", "questionId", "问题已删除，无法保存答复回执")
        val saved = lifetimes.sanitize(key, reply)
        toolReceipts[key] = saved
        persist(root.resolve("tool-receipts.json"), toolReceipts.map { TutorialToolReceipt(it.key, it.value) })
        return saved
    }

    @Synchronized fun saveSource(value: TutorialSource): TutorialSource {
        sources.values.firstOrNull { it.copy(id = value.id) == value }?.let { return it }
        writable()
        sources[value.id] = value
        persist(root.resolve("sources.json"), sources.values.toList())
        return value
    }

    @Synchronized fun saveContext(value: TutorialContext): TutorialContext {
        writable()
        if (contexts[value.id] == value) return value
        contexts[value.id] = value
        persist(root.resolve("contexts.json"), contexts.values.toList())
        return value
    }

    @Synchronized fun creation(contextId: String, title: String, markdown: String, resource: TutorialResourceState): TutorialRecord? =
        lifetimes.creation(lifetimes.creationKey(contextId, title, markdown, resource))?.let { available(it); records[it]?.also { editable(it.id) } }

    @Synchronized fun registerToolWrite(key: String, target: String?) {
        if (target == null) return
        writable()
        val id = if (target.startsWith("tut_")) target else annotation(target).materialId
        editable(id)
        lifetimes.bindRequest(id, key)
        writer.write(root.resolve("lifetimes.json")) { lifetimes.persist() }
    }

    @Synchronized fun create(contextId: String, title: String, markdown: String, resource: TutorialResourceState, receiptKey: String? = null): TutorialRecord {
        writable()
        creation(contextId, title, markdown, resource)?.let { existing ->
            if (receiptKey != null) {
                registerToolWrite(receiptKey, existing.id)
                rememberToolReply(receiptKey, mapOf("tutorial" to existing, "resources" to resource(existing.id)))
                persistContent(existing.id)
            }
            return existing
        }
        context(contextId)
        val content = markdown.replace("\r\n", "\n")
        validateTutorial(content, resource)
        val normalized = resource.copy(diagrams = resource.diagrams.map { it.copy(revision = 1) }, simulations = resource.simulations.map { it.copy(revision = 1) })
        // 响应丢失后的相同提交返回已创建产物；不把 UUID 负担交给 Agent。
        records.values.firstOrNull { it.contextId == contextId && it.title == title && it.markdown == content && resources[it.id] == normalized }?.let {
            editable(it.id)
            lifetimes.rememberCreation(it.id, lifetimes.creationKey(contextId, title, content, normalized))
            receiptKey?.let { key -> lifetimes.bindRequest(it.id, key) }
            writer.write(root.resolve("lifetimes.json")) { lifetimes.persist() }
            rememberToolReply(receiptKey, mapOf("tutorial" to it, "resources" to normalized))
            if (receiptKey != null) persistContent(it.id)
            return it
        }
        val id = lifetimes.creation(lifetimes.creationKey(contextId, title, content, normalized)) ?: newId("tut")
        tutorialRequire(title.isNotBlank(), "EMPTY_TITLE", "title", "教程标题不能为空")
        val record = TutorialRecord(id, 1, title, content, contextId, associatedSources(contextId, content, normalized), System.currentTimeMillis())
        lifetimes.rememberCreation(id, lifetimes.creationKey(contextId, title, content, normalized))
        receiptKey?.let { lifetimes.bindRequest(id, it) }
        writer.write(root.resolve("lifetimes.json")) { lifetimes.persist() }
        resources[id] = normalized
        records[id] = record
        rememberToolReply(receiptKey, mapOf("tutorial" to record, "resources" to normalized))
        persistContent(id)
        return record
    }

    @Synchronized fun change(id: String, expected: Int, markdown: String, offset: Int, removed: Int, inserted: Int): TutorialRecord {
        writable()
        editable(id)
        val old = record(id)
        if (old.markdown == markdown) return old
        if (old.revision != expected) throw TutorialProblem("REVISION_CONFLICT", "expectedRevision", "正文已更新", old.revision)
        val updated = old.copy(markdown = markdown, revision = old.revision + 1, sourceIds = associatedSources(old.contextId, markdown, resource(id)))
        records[id] = updated
        annotations(id).forEach {
            var relocated = relocateAnnotation(it, offset, removed, inserted)
            if (!it.orphaned && relocated.orphaned) relocated = relocated.copy(relocatable = occurrences(old.markdown, it.quote).size == 1)
            val found = if (relocated.orphaned) occurrences(markdown, relocated.quote) else emptyList()
            annotations[it.id] = if (relocated.relocatable && found.size == 1) relocated.copy(start = found.single(), end = found.single() + relocated.quote.length, orphaned = false, relocatable = false) else relocated
        }
        persistContent(id)
        return updated
    }

    @Synchronized fun saveDiagram(tutorialId: String, value: TutorialDiagram, expected: Int, receiptKey: String? = null): TutorialDiagram {
        writable()
        editable(tutorialId)
        validateTutorialGraph(value.graph)
        uniqueIds(listOf(value.id), "id")
        val state = resource(tutorialId)
        val old = state.diagrams.find { it.id == value.id }
        if (old?.graph == value.graph) { rememberToolReply(receiptKey, old); if (receiptKey != null) persistContent(tutorialId); return old }
        checkRevision(old?.revision ?: 0, expected)
        val saved = value.copy(revision = expected + 1)
        rememberToolReply(receiptKey, saved)
        saveResources(tutorialId, state.copy(diagrams = state.diagrams.filterNot { it.id == value.id } + saved))
        return saved
    }

    @Synchronized fun saveSimulation(tutorialId: String, value: TutorialSimulation, expected: Int, receiptKey: String? = null): TutorialSimulation {
        writable()
        editable(tutorialId)
        validateSimulation(value)
        val state = resource(tutorialId)
        val old = state.simulations.find { it.id == value.id }
        if (old != null && old.copy(revision = 0) == value.copy(revision = 0)) { rememberToolReply(receiptKey, old); if (receiptKey != null) persistContent(tutorialId); return old }
        checkRevision(old?.revision ?: 0, expected)
        val saved = value.copy(revision = expected + 1)
        rememberToolReply(receiptKey, saved)
        saveResources(tutorialId, state.copy(simulations = state.simulations.filterNot { it.id == value.id } + saved))
        return saved
    }

    private fun checkRevision(current: Int, expected: Int) {
        if (current != expected) throw TutorialProblem("REVISION_CONFLICT", "expectedRevision", "资源已更新", current)
    }

    @Synchronized fun savePositions(id: String, resourceId: String, positions: Map<String, TutorialPosition>) {
        writable()
        editable(id)
        val state = resource(id)
        saveResources(id, state.copy(positions = state.positions + (resourceId to positions)))
    }

    private fun saveResources(id: String, state: TutorialResourceState) {
        editable(id)
        resources[id] = state
        val old = record(id)
        val current = old.copy(sourceIds = associatedSources(old.contextId, old.markdown, state))
        records[id] = current
        persistContent(id)
    }

    private fun associatedSources(contextId: String, markdown: String, state: TutorialResourceState): List<String> = (
        context(contextId).sourceIds + Regex("lessplain://source/([^\\s)]+)").findAll(markdown).map { it.groupValues[1] }.toList() +
            state.diagrams.flatMap { diagram -> diagram.graph.nodes.mapNotNull { it.sourceId } + diagram.graph.evidence.flatMap { listOf(it.sourceId) + it.dependencySourceIds } }
        ).distinct().filter { it in sources }

    fun flush() {
        val (pending, removals) = synchronized(this) {
            tutorialRequire(!closed, "CLOSED", "", "教程存储已关闭")
            writer.checkpoint() to removedContexts.toMap()
        }
        pending.get()?.let { throw TutorialProblem("PERSISTENCE_FAILED", "", "教程持久化失败：${it.message}") }
        // 检查点已覆盖这些上下文的清理；只压缩当时捕获的登记，不越过并发的新删除。
        val compacted = synchronized(this) {
            if (closed || removals.isEmpty()) null else {
                removals.forEach { (id, ids) ->
                    val remaining = removedContexts[id].orEmpty() - ids
                    if (remaining.isEmpty()) removedContexts.remove(id) else removedContexts[id] = remaining
                    if (id in records && !isUnavailable(id)) persistContent(id)
                }
                writer.checkpoint()
            }
        }
        compacted?.get()?.let { throw TutorialProblem("PERSISTENCE_FAILED", "", "删除登记压缩失败：${it.message}") }
    }

    /** Document 的多处替换在一次事务中归并，失败时不发布半份模型或回执。 */
    @Synchronized internal fun edit(id: String, key: String, action: () -> Unit): TutorialRecord {
        writable(); editable(id)
        check(editing == null)
        val before = record(id)
        val notes = annotations(id)
        editing = id
        try {
            action()
            receipts[key] = record(id)
        } catch (failure: Throwable) {
            records[id] = before
            annotations.entries.removeIf { it.value.materialId == id }
            notes.forEach { annotations[it.id] = it }
            throw failure
        } finally { editing = null }
        persistContent(id)
        return record(id)
    }

    private fun persistContent(id: String) {
        if (editing == id) return
        persist(root.resolve(id).resolve("content.json"), contentState(id))
    }

    private fun rememberToolReply(key: String?, value: Any) {
        if (key != null) toolReceipts[key] = gson.toJson(mapOf("ok" to true, "data" to value))
    }

    private fun contentState(id: String) = TutorialContentState(record(id), annotations(id), receipts.filterValues { it.id == id },
        resource(id), toolReceipts.filterKeys { lifetimes.requestOwner(it) == id }, removedContexts[id].orEmpty())

    @Synchronized fun createAnnotation(value: TutorialAnnotation): TutorialAnnotation {
        writable()
        editable(value.materialId)
        tutorialRequire(value.id !in annotations, "DUPLICATE_ID", "id", "批注已存在")
        value.parentId?.let { parentId ->
            val parent = annotation(parentId)
            tutorialRequire(parent.kind == "question" && parent.materialId == value.materialId, "INVALID_PARENT", "parentId", "后续追问必须属于同一教程的问题")
        }
        annotations[value.id] = value
        persistContent(value.materialId)
        return value
    }

    /** 答复与正文重定位竞争时，保留事务开始时最新的范围和用户数据。 */
    @Synchronized fun updateAnnotation(id: String, receiptKey: String? = null, update: (TutorialAnnotation) -> TutorialAnnotation): TutorialAnnotation {
        writable()
        val old = annotation(id)
        editable(old.materialId)
        val saved = update(old)
        require(saved.id == old.id && saved.materialId == old.materialId && saved.parentId == old.parentId)
        annotations[id] = saved
        rememberToolReply(receiptKey, saved)
        persistContent(saved.materialId)
        return saved
    }

    /** 空草稿只在窗口中存在；状态检查与删除/保存必须同锁，不能清掉并发到达的答复。 */
    @Synchronized internal fun saveAnnotationDraft(draft: TutorialAnnotation, text: String, allowCreate: Boolean): TutorialAnnotation? {
        writable()
        editable(draft.materialId)
        val current = annotations[draft.id]
        if (current == null && !allowCreate) annotation(draft.id)
        if (current != null && current.kind == "question" && current.status != "draft") return current
        if (text.isBlank()) {
            if (current != null) {
                annotations.remove(current.id)
                persistContent(current.materialId)
            }
            return null
        }
        return if (current == null) createAnnotation(draft.copy(text = text))
        else updateAnnotation(current.id) { it.copy(text = text) }
    }

    @Synchronized fun annotationBranch(id: String): List<TutorialAnnotation> {
        val root = annotation(id)
        val ids = linkedSetOf(id)
        var count: Int
        do {
            count = ids.size
            annotations.values.filter { it.materialId == root.materialId && it.parentId in ids }.forEach { ids += it.id }
        } while (count != ids.size)
        return ids.map(::annotation)
    }

    /** 显式删除整条问答。独立产物及它们仍引用的上下文不受影响。 */
    @Synchronized fun deleteAnnotation(id: String): List<TutorialAnnotation> {
        writable()
        val branch = annotationBranch(id)
        editable(branch.first().materialId)
        val ids = branch.map { it.id }.toSet()
        val owner = branch.first().materialId
        val next = contentState(owner).let { state -> state.copy(
            annotations = state.annotations.filterNot { it.id in ids },
            toolReceipts = state.toolReceipts?.filterValues { annotationReceiptId(it) !in ids },
            removedContexts = state.removedContexts.orEmpty() + branch.mapNotNull { it.contextId },
        ) }
        val path = root.resolve(owner).resolve("content.json")
        val serialized = gson.toJson(next)
        try { writer.commit(path) { writeTutorialJson(path, serialized) }.get() }
        catch (failure: Exception) { throw TutorialProblem("PERSISTENCE_FAILED", "id", "批注删除未保存，请重试：${failure.cause?.message ?: failure.message}") }
        removedContexts[owner] = next.removedContexts.orEmpty()
        branch.forEach { annotations.remove(it.id) }
        toolReceipts.entries.removeIf { annotationReceiptId(it.value) in ids }
        val retainedContexts = records.values.map { it.contextId }.toSet() + annotations.values.mapNotNull { it.contextId }
        branch.mapNotNull { it.contextId }.filterNot { it in retainedContexts }.forEach(contexts::remove)
        persist(root.resolve("contexts.json"), contexts.values.toList())
        persist(root.resolve("tool-receipts.json"), toolReceipts.map { TutorialToolReceipt(it.key, it.value) })
        return branch
    }

    private fun annotationReceiptId(reply: String): String? {
        val data = gson.fromJson(reply, com.google.gson.JsonObject::class.java)?.get("data")
        if (data == null || !data.isJsonObject) return null
        return data.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.startsWith("q_") || it.startsWith("note_") }
    }

    @Synchronized fun saveReading(id: String, state: TutorialReadingState) {
        writable()
        editable(id)
        reading[id] = state
        persist(root.resolve(id).resolve("reading.json"), state)
    }

    /** 删除登记先落盘，目录清理排在全部旧保存之后；登记成功后任何迟到写入均拒绝。 */
    @Synchronized fun beginDeletion(id: String) { writable(); editable(id); deleting += id }

    fun deleteTutorial(id: String) {
        val committed = synchronized(this) {
            writable()
            if (lifetimes.deleted(id)) null else {
                record(id)
                deleting += id
                val ids = annotations(id).map { it.id }.toSet()
                val contextIds = (listOfNotNull(records[id]?.contextId) + annotations(id).mapNotNull { it.contextId } + contexts.values.filter { it.materialId == id }.map { it.id } + removedContexts[id].orEmpty()).toSet()
                writer.submit { lifetimes.commitDeletion(id, ids, contextIds) }
            }
        }
        try { committed?.get() } catch (failure: Exception) {
            synchronized(this) { deleting -= id }
            throw TutorialProblem("PERSISTENCE_FAILED", "id", "删除登记保存失败，可重试：${failure.cause?.message ?: failure.message}")
        }
        val cleanup = synchronized(this) {
            val contextIds = listOfNotNull(records[id]?.contextId) + annotations(id).mapNotNull { it.contextId } + contexts.values.filter { it.materialId == id }.map { it.id } + removedContexts[id].orEmpty()
            val ownedResources = resources[id]
            records.remove(id); resources.remove(id); reading.remove(id); removedContexts.remove(id)
            annotations.entries.removeIf { it.value.materialId == id }
            receipts.entries.removeIf { it.value.id == id }
            writer.discard(root.resolve(id))
            val retained = records.values.map { it.contextId }.toSet() + annotations.values.mapNotNull { it.contextId }
            contextIds.filterNot { it in retained }.forEach(contexts::remove)
            val legacyResources = ownedResources?.let { state -> (state.diagrams.map(gson::toJson) + state.simulations.map(gson::toJson)).toSet() }.orEmpty()
            toolReceipts.entries.removeIf { (_, reply) ->
                val data = gson.fromJson(reply, com.google.gson.JsonObject::class.java)?.get("data")
                data?.toString() in legacyResources
            }
            toolReceipts.replaceAll { key, reply -> lifetimes.sanitize(key, reply) }
            persist(root.resolve("contexts.json"), contexts.values.toList())
            persist(root.resolve("receipts.json"), receipts.map { TutorialEditReceipt(it.key, it.value) })
            persist(root.resolve("tool-receipts.json"), toolReceipts.map { TutorialToolReceipt(it.key, it.value) })
            deleting -= id
            writer.submit { cleanupDeleted(root, id) }
        }
        try { cleanup.get(); flush() } catch (failure: Exception) {
            throw TutorialProblem("CLEANUP_PENDING", "id", "教程已删除，但文件清理失败；重试或重开项目将继续清理：${failure.cause?.message ?: failure.message}")
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            // 锁随最后一项磁盘任务释放，超时返回也不能让另一个实例与尚未完成的写入竞争。
            writer.retryPending()
            writer.submit { try { writerLock?.release() } finally { lockChannel.close() } }
            writer.shutdown()
        }
        writer.awaitTermination(10, TimeUnit.SECONDS)
    }
}

internal fun newId(prefix: String) = "${prefix}_${UUID.randomUUID()}"

private data class TutorialEditReceipt(val key: String, val record: TutorialRecord)
private data class TutorialToolReceipt(val key: String, val reply: String)
