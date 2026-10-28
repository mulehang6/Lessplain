package com.lessplain.tutorial

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lessplain.storage.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** 只保存身份和请求摘要；删除后不会在回执中保留正文或回答。磁盘写入由 Store 的串行队列调用。 */
internal class TutorialLifetimes(private val root: Path) {
    private val gson = Gson()
    private val path = root.resolve("lifetimes.json")
    private val entries = ConcurrentHashMap<String, Lifetime>()
    init {
        if (Files.exists(path)) gson.fromJson(Files.readString(path), Array<Lifetime>::class.java)?.forEach { entries[it.id] = it }
    }
    private data class Lifetime(val id: String, val creations: Set<String> = emptySet(), val requests: Set<String> = emptySet(),
        val deleted: Boolean = false, val annotations: Set<String> = emptySet(), val contexts: Set<String> = emptySet())

    fun creationKey(contextId: String, title: String, markdown: String, state: TutorialResourceState): String = sha256(gson.toJson(listOf(
        contextId, title, markdown.replace("\r\n", "\n"), state.copy(positions = emptyMap(),
            diagrams = state.diagrams.map { it.copy(revision = 1) }, simulations = state.simulations.map { it.copy(revision = 1) }),
    )))
    fun creation(key: String): String? = entries.values.firstOrNull { key in it.creations }?.id
    fun rememberCreation(id: String, key: String) { entries.compute(id) { _, old -> (old ?: Lifetime(id)).let { it.copy(creations = it.creations + key) } } }
    fun bindRequest(id: String, key: String) { entries.compute(id) { _, old -> (old ?: Lifetime(id)).let { it.copy(requests = it.requests + key) } } }
    fun deleted(id: String) = entries[id]?.deleted == true
    fun deletedIds() = entries.values.filter { it.deleted }.map { it.id }
    fun deletedAnnotation(id: String) = entries.values.firstOrNull { it.deleted && id in it.annotations }?.id
    fun requestOwner(key: String) = entries.values.firstOrNull { key in it.requests }?.id
    fun deletedContexts() = entries.values.filter { it.deleted }.flatMap { it.contexts }.toSet()
    fun deletedRequest(key: String) = entries.values.firstOrNull { it.deleted && key in it.requests }?.id
    fun persist() = writeTutorialJson(path, gson.toJson(entries.values.sortedBy { it.id }))
    fun commitDeletion(id: String, annotations: Set<String>, contexts: Set<String>) {
        val next = (entries[id] ?: Lifetime(id)).copy(deleted = true, annotations = annotations, contexts = contexts)
        val snapshot = entries.toMutableMap().apply { put(id, next) }
        writeTutorialJson(path, gson.toJson(snapshot.values.sortedBy { it.id }))
        entries[id] = next
    }
    fun legacyResource(key: String, reply: String): Boolean {
        val data = JsonParser.parseString(reply).asJsonObject.getAsJsonObject("data") ?: return false
        return requestOwner(key) == null && data.has("id") && (data.has("scenarios") || data.has("graph")) && !data.has("tutorial")
    }
    fun responseOwner(reply: String): String? {
        val data = JsonParser.parseString(reply).asJsonObject.getAsJsonObject("data") ?: return null
        val nested = data.getAsJsonObject("tutorial")
        return (nested?.get("id") ?: data.get("materialId") ?: data.get("id"))?.takeIf { it.isJsonPrimitive }?.asString
    }
    fun deletedReply(id: String): String = JsonObject().apply {
        addProperty("ok", false)
        add("error", JsonObject().apply { addProperty("code", "DELETED"); addProperty("field", "id"); addProperty("message", "教程已删除：$id") })
    }.toString()
    fun sanitize(key: String, reply: String): String {
        val id = deletedRequest(key) ?: responseOwner(reply)?.let { owner ->
            owner.takeIf(::deleted) ?: deletedAnnotation(owner)
        }
        return id?.let(::deletedReply) ?: reply
    }
    fun bootstrap(key: String, reply: String) {
        val data = JsonParser.parseString(reply).asJsonObject.getAsJsonObject("data") ?: return
        val tutorial = data.getAsJsonObject("tutorial") ?: return
        val record = gson.fromJson(tutorial, TutorialRecord::class.java)
        val resources = data.getAsJsonObject("resources")?.let { gson.fromJson(it, TutorialResourceState::class.java) } ?: TutorialResourceState()
        rememberCreation(record.id, creationKey(record.contextId, record.title, record.markdown, resources))
        bindRequest(record.id, key)
    }
}

/** 临时文件与目标在同一目录；失败不会替换已提交的删除登记。 */
internal fun writeTutorialJson(path: Path, text: String) {
    Files.createDirectories(path.parent)
    val temp = path.resolveSibling("${path.fileName}.tmp")
    Files.writeString(temp, text)
    try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
}

/** 仅删除确定的教程子目录，不跟随目录中的符号链接。 */
internal fun cleanTutorialDirectory(root: Path, id: String) {
    require(id.matches(Regex("tut_[A-Za-z0-9_-]+")))
    val base = root.toAbsolutePath().normalize()
    val target = base.resolve(id).normalize()
    require(target.parent == base)
    if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        val paths = Files.walk(target).use { it.toList().sortedDescending() }
        paths.forEach(Files::deleteIfExists)
    }
}
