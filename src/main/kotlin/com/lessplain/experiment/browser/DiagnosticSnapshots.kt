package com.lessplain.experiment.browser

import com.intellij.openapi.application.PathManager
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 仅异常时由后台调用。文件属于插件独立目录，不包含页面正文、问题或 bridge payload。 */
internal object DiagnosticSnapshots {
    @Synchronized fun save(host: String, reason: String, events: JsonArray): Path {
        val directory = Path.of(PathManager.getLogPath(), "lessplain-diagnostics")
        Files.createDirectories(directory)
        val threads = Thread.getAllStackTraces().entries.take(200).map { (thread, stack) -> buildJsonObject {
            put("name", thread.name.take(120)); put("state", thread.state.name)
            put("stack", JsonArray(stack.take(40).map { JsonPrimitive(it.toString()) }))
        } }
        val snapshot = buildJsonObject {
            put("host", host); put("reason", reason); put("events", events); put("threads", JsonArray(threads))
        }
        val file = directory.resolve("lessplain-${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
        Files.writeString(file, snapshot.toString())
        val owned = Files.list(directory).use { stream -> stream.filter {
            Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().matches(Regex("lessplain-[0-9]+-[0-9a-f-]+\\.json"))
        }.sorted(compareBy<Path> { Files.getLastModifiedTime(it).toMillis() }).toList() }
        var bytes = owned.sumOf { Files.size(it) }
        var count = owned.size
        for (old in owned) {
            if (count <= 10 && bytes <= 50_000_000) break
            bytes -= Files.size(old); Files.deleteIfExists(old); count--
        }
        return file
    }
}
