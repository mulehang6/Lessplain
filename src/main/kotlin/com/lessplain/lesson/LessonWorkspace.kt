package com.lessplain.lesson

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 项目隔离的持久目录；调用者在后台线程使用，界面仅读取不可变 snapshot。 */
class LessonWorkspace(val root: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    @Volatile var snapshot: LessonState = LessonState()
        private set

    @Synchronized
    fun load(): LessonState {
        val path = root.resolve("lessons.json")
        if (!Files.exists(path)) return snapshot
        val loaded = requireNotNull(gson.fromJson(Files.readString(path), LessonState::class.java)) { "教材状态为空" }
        require(loaded.schemaVersion == 1) { "教材数据版本不受支持：${loaded.schemaVersion}" }
        validateLessonState(loaded)
        snapshot = loaded
        return loaded
    }

    @Synchronized
    fun update(transform: (LessonState) -> LessonState): LessonState {
        val next = transform(snapshot)
        Files.createDirectories(root)
        atomicWrite(root.resolve("lessons.json"), gson.toJson(next))
        snapshot = next
        return next
    }

    fun lesson(id: String): LessonRecord = snapshot.lessons.firstOrNull { it.id == id }
        ?: error("教材不存在：$id")

    fun examplePath(record: LessonRecord, example: LessonExample): Path {
        validateLesson(record)
        return root.resolve("examples").resolve(record.id).resolve("r${record.revision}")
            .resolve(example.id).resolve(example.fileName)
    }

    /** 只创建缺失文件；学习者修改过的脚本不会因保存笔记或重新打开教材被覆盖。 */
    fun materializeExamples(record: LessonRecord) {
        validateLesson(record)
        record.examples.forEach { example ->
            val path = examplePath(record, example)
            if (!Files.exists(path)) {
                Files.createDirectories(path.parent)
                atomicWrite(path, lessonScript(example))
            }
        }
    }

    fun exampleText(record: LessonRecord, example: LessonExample): String {
        val path = examplePath(record, example)
        return if (Files.exists(path)) Files.readString(path) else lessonScript(example)
    }

    private fun atomicWrite(path: Path, text: String) {
        val temporary = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, text)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

internal fun lessonScript(example: LessonExample): String = buildString {
    example.dependencies.distinct().forEach { append("@file:DependsOn(\"").append(it).append("\")\n") }
    if (example.dependencies.isNotEmpty()) append('\n')
    append(example.code)
    if (!example.code.endsWith('\n')) append('\n')
}
