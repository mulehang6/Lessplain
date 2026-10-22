package com.lessplain.tutorial

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 正文、资源、批注、阅读状态分别保存；单线程原子替换文件，关闭时排空写入。 */
class TutorialStore(private val root: Path) : AutoCloseable {
    private val gson = Gson()
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "Lessplain tutorial persistence").apply { isDaemon = true } }
    private val records = linkedMapOf<String, TutorialRecord>()
    private val resources = linkedMapOf<String, TutorialResourceState>()
    private val annotations = linkedMapOf<String, TutorialAnnotation>()
    private val contexts = linkedMapOf<String, TutorialContext>()
    private val sources = linkedMapOf<String, TutorialSource>()
    private val reading = linkedMapOf<String, TutorialReadingState>()
    private val receipts = linkedMapOf<String, TutorialRecord>()
    private val toolReceipts = linkedMapOf<String, String>()
    @Volatile var writeFailure: Throwable? = null
        private set

    init {
        Files.createDirectories(root)
        Files.list(root).use { paths -> paths.filter { Files.isDirectory(it) }.forEach { path ->
            read(path.resolve("document.json"), TutorialRecord::class.java)?.let { records[it.id] = it }
            read(path.resolve("resources.json"), TutorialResourceState::class.java)?.let { resources[path.fileName.toString()] = it }
            read(path.resolve("annotations.json"), Array<TutorialAnnotation>::class.java)?.forEach { annotations[it.id] = it }
            read(path.resolve("reading.json"), TutorialReadingState::class.java)?.let { reading[path.fileName.toString()] = it }
        } }
        read(root.resolve("contexts.json"), Array<TutorialContext>::class.java)?.forEach { contexts[it.id] = it }
        read(root.resolve("sources.json"), Array<TutorialSource>::class.java)?.forEach { sources[it.id] = it }
        read(root.resolve("receipts.json"), Array<TutorialEditReceipt>::class.java)?.forEach { receipts[it.key] = it.record }
        read(root.resolve("tool-receipts.json"), Array<TutorialToolReceipt>::class.java)?.forEach { toolReceipts[it.key] = it.reply }
    }

    private fun <T> read(path: Path, type: Class<T>): T? = if (Files.exists(path)) gson.fromJson(Files.readString(path), type) else null
    private fun persist(path: Path, value: Any) {
        val text = gson.toJson(value)
        writer.submit {
            try {
                Files.createDirectories(path.parent)
                val temp = path.resolveSibling("${path.fileName}.tmp")
                Files.writeString(temp, text)
                try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
            } catch (failure: Exception) { writeFailure = failure }
        }
    }

    @Synchronized fun list() = records.values.toList()
    @Synchronized fun record(id: String): TutorialRecord = records[id] ?: throw TutorialProblem("NOT_FOUND", "id", "教程不存在：$id")
    @Synchronized fun resource(id: String) = resources[id] ?: TutorialResourceState()
    @Synchronized fun context(id: String) = contexts[id] ?: throw TutorialProblem("NOT_FOUND", "contextId", "冻结上下文不存在：$id")
    @Synchronized fun source(id: String) = sources[id] ?: throw TutorialProblem("NOT_FOUND", "sourceId", "源码引用不存在：$id")
    @Synchronized fun sources() = sources.values.toList()
    @Synchronized fun annotations(id: String? = null) = annotations.values.filter { id == null || it.materialId == id }
    @Synchronized fun annotation(id: String) = annotations[id] ?: throw TutorialProblem("NOT_FOUND", "id", "批注不存在：$id")
    @Synchronized fun reading(id: String) = reading[id] ?: TutorialReadingState()
    @Synchronized fun receipt(key: String) = receipts[key]
    @Synchronized fun toolReceipt(key: String) = toolReceipts[key]
    @Synchronized fun saveToolReceipt(key: String, reply: String) {
        val annotationId = annotationReceiptId(reply)
        if (annotationId != null && annotationId !in annotations) return // 删除与答复回执竞争时，不再缓存已删除问答。
        toolReceipts[key] = reply
        persist(root.resolve("tool-receipts.json"), toolReceipts.map { TutorialToolReceipt(it.key, it.value) })
    }
    @Synchronized fun saveReceipt(key: String, record: TutorialRecord) {
        receipts[key] = record
        persist(root.resolve("receipts.json"), receipts.map { TutorialEditReceipt(it.key, it.value) })
    }

    @Synchronized fun saveSource(value: TutorialSource): TutorialSource {
        sources.values.firstOrNull { it.copy(id = value.id) == value }?.let { return it }
        sources[value.id] = value
        persist(root.resolve("sources.json"), sources.values.toList())
        return value
    }

    @Synchronized fun saveContext(value: TutorialContext): TutorialContext {
        contexts[value.id] = value
        persist(root.resolve("contexts.json"), contexts.values.toList())
        return value
    }

    @Synchronized fun create(contextId: String, title: String, markdown: String, resource: TutorialResourceState): TutorialRecord {
        val context = context(contextId)
        validateTutorial(markdown, resource)
        val normalized = resource.copy(diagrams = resource.diagrams.map { it.copy(revision = 1) }, simulations = resource.simulations.map { it.copy(revision = 1) })
        // 响应丢失后的相同提交返回已创建产物；不把 UUID 负担交给 Agent。
        records.values.firstOrNull { it.contextId == contextId && it.title == title && it.markdown == markdown && resources[it.id] == normalized }?.let { return it }
        val id = newId("tut")
        tutorialRequire(title.isNotBlank(), "EMPTY_TITLE", "title", "教程标题不能为空")
        val record = TutorialRecord(id, 1, title, markdown.replace("\r\n", "\n"), contextId, associatedSources(contextId, markdown, normalized), System.currentTimeMillis())
        resources[id] = normalized
        persist(root.resolve(id).resolve("resources.json"), normalized)
        records[id] = record
        persist(root.resolve(id).resolve("document.json"), record)
        return record
    }

    @Synchronized fun change(id: String, expected: Int, markdown: String, offset: Int, removed: Int, inserted: Int): TutorialRecord {
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
        persist(root.resolve(id).resolve("document.json"), updated)
        persistAnnotations(id)
        return updated
    }

    @Synchronized fun saveDiagram(tutorialId: String, value: TutorialDiagram, expected: Int): TutorialDiagram {
        record(tutorialId)
        validateTutorialGraph(value.graph)
        uniqueIds(listOf(value.id), "id")
        val state = resource(tutorialId)
        val old = state.diagrams.find { it.id == value.id }
        if (old?.graph == value.graph) return old
        checkRevision(old?.revision ?: 0, expected)
        val saved = value.copy(revision = expected + 1)
        saveResources(tutorialId, state.copy(diagrams = state.diagrams.filterNot { it.id == value.id } + saved))
        return saved
    }

    @Synchronized fun saveSimulation(tutorialId: String, value: TutorialSimulation, expected: Int): TutorialSimulation {
        record(tutorialId)
        validateSimulation(value)
        val state = resource(tutorialId)
        val old = state.simulations.find { it.id == value.id }
        if (old != null && old.copy(revision = 0) == value.copy(revision = 0)) return old
        checkRevision(old?.revision ?: 0, expected)
        val saved = value.copy(revision = expected + 1)
        saveResources(tutorialId, state.copy(simulations = state.simulations.filterNot { it.id == value.id } + saved))
        return saved
    }

    private fun checkRevision(current: Int, expected: Int) {
        if (current != expected) throw TutorialProblem("REVISION_CONFLICT", "expectedRevision", "资源已更新", current)
    }

    @Synchronized fun savePositions(id: String, resourceId: String, positions: Map<String, TutorialPosition>) {
        val state = resource(id)
        saveResources(id, state.copy(positions = state.positions + (resourceId to positions)))
    }

    private fun saveResources(id: String, state: TutorialResourceState) {
        resources[id] = state
        persist(root.resolve(id).resolve("resources.json"), state)
        val old = record(id)
        val current = old.copy(sourceIds = associatedSources(old.contextId, old.markdown, state))
        records[id] = current
        persist(root.resolve(id).resolve("document.json"), current)
    }

    private fun associatedSources(contextId: String, markdown: String, state: TutorialResourceState): List<String> = (
        context(contextId).sourceIds + Regex("lessplain://source/([^\\s)]+)").findAll(markdown).map { it.groupValues[1] }.toList() +
            state.diagrams.flatMap { diagram -> diagram.graph.nodes.mapNotNull { it.sourceId } + diagram.graph.evidence.flatMap { listOf(it.sourceId) + it.dependencySourceIds } }
        ).distinct().filter { it in sources }

    fun flush() {
        writer.submit {}.get()
        writeFailure?.let { throw TutorialProblem("PERSISTENCE_FAILED", "", "教程持久化失败：${it.message}") }
    }

    @Synchronized fun createAnnotation(value: TutorialAnnotation): TutorialAnnotation {
        record(value.materialId)
        tutorialRequire(value.id !in annotations, "DUPLICATE_ID", "id", "批注已存在")
        value.parentId?.let { parentId ->
            val parent = annotation(parentId)
            tutorialRequire(parent.kind == "question" && parent.materialId == value.materialId, "INVALID_PARENT", "parentId", "后续追问必须属于同一教程的问题")
        }
        annotations[value.id] = value
        persistAnnotations(value.materialId)
        return value
    }

    /** 答复与正文重定位竞争时，保留事务开始时最新的范围和用户数据。 */
    @Synchronized fun updateAnnotation(id: String, update: (TutorialAnnotation) -> TutorialAnnotation): TutorialAnnotation {
        val old = annotation(id)
        val saved = update(old)
        require(saved.id == old.id && saved.materialId == old.materialId && saved.parentId == old.parentId)
        annotations[id] = saved
        persistAnnotations(saved.materialId)
        return saved
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
        val branch = annotationBranch(id)
        val ids = branch.map { it.id }.toSet()
        branch.forEach { annotations.remove(it.id) }
        toolReceipts.entries.removeIf { annotationReceiptId(it.value) in ids }
        val retainedContexts = records.values.map { it.contextId }.toSet() + annotations.values.mapNotNull { it.contextId }
        branch.mapNotNull { it.contextId }.filterNot { it in retainedContexts }.forEach(contexts::remove)
        persistAnnotations(branch.first().materialId)
        persist(root.resolve("contexts.json"), contexts.values.toList())
        persist(root.resolve("tool-receipts.json"), toolReceipts.map { TutorialToolReceipt(it.key, it.value) })
        return branch
    }

    private fun annotationReceiptId(reply: String): String? {
        val data = gson.fromJson(reply, com.google.gson.JsonObject::class.java)?.get("data")
        if (data == null || !data.isJsonObject) return null
        return data.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.startsWith("q_") || it.startsWith("note_") }
    }

    private fun persistAnnotations(id: String) = persist(root.resolve(id).resolve("annotations.json"), annotations(id))

    @Synchronized fun saveReading(id: String, state: TutorialReadingState) {
        reading[id] = state
        persist(root.resolve(id).resolve("reading.json"), state)
    }

    override fun close() { writer.shutdown(); writer.awaitTermination(10, TimeUnit.SECONDS) }
}

internal fun newId(prefix: String) = "${prefix}_${UUID.randomUUID()}"

private data class TutorialEditReceipt(val key: String, val record: TutorialRecord)
private data class TutorialToolReceipt(val key: String, val reply: String)
