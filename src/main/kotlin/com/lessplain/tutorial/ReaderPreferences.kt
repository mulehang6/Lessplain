package com.lessplain.tutorial

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/** 用户自行维护的讲解要求与项目背景，不从阅读或提问记录推断能力。 */
data class ReaderPreferences(
    val guidance: String = "",
    val projectBackground: String = "",
)

/** 通用偏好属于本机用户，项目背景只属于当前项目的个人工作区设置。 */
internal class ReaderPreferencesStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    constructor(project: Project) : this(
        { key -> properties(project, key).getValue(key) },
        { key, value -> properties(project, key).setValue(key, value) },
    )

    fun snapshot() = ReaderPreferences(read(GUIDANCE).orEmpty(), read(BACKGROUND).orEmpty())

    fun save(preferences: ReaderPreferences) {
        write(GUIDANCE, preferences.guidance.replace("\r\n", "\n").trim())
        write(BACKGROUND, preferences.projectBackground.replace("\r\n", "\n").trim())
    }

    companion object {
        private const val GUIDANCE = "lessplain.reader.guidance"
        private const val BACKGROUND = "lessplain.reader.projectBackground"

        private fun properties(project: Project, key: String): PropertiesComponent =
            if (key == GUIDANCE) PropertiesComponent.getInstance() else PropertiesComponent.getInstance(project)
    }
}
