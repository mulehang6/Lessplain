package com.lessplain.lesson

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

/** 仅委托 IDE 已安装的 Kotlin Script producer 和执行器，不反射 Kotlin 内部配置类。 */
internal class LessonScriptRuntime(private val project: Project, private val service: LessonService) {
    fun open(record: LessonRecord, example: LessonExample, executorId: String? = null) {
        service.background({
            service.workspace.materializeExamples(record)
            requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(service.workspace.examplePath(record, example))) {
                "示例文件无法打开"
            }
        }) { file ->
            FileEditorManager.getInstance(project).openFile(file, true)
            if (executorId != null) DumbService.getInstance(project).runWhenSmart {
                if (project.isDisposed) return@runWhenSmart
                lessonUiRead {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                    val psi = PsiManager.getInstance(project).findFile(file)
                    val settings = psi?.let { ConfigurationContext(it).configurationsFromContext }
                        ?.firstOrNull { it.configurationSettings.type.id == "KotlinStandaloneScriptRunConfigurationType" }
                        ?.configurationSettings
                    val executor = ExecutorRegistry.getInstance().getExecutorById(executorId)
                    if (settings == null || executor == null) {
                        Messages.showInfoMessage(project,
                            "请确认 Kotlin 插件已启用，并在已打开的示例文件中完成 IDE 的脚本依赖解析。然后可使用编辑器原生的运行或 Debug 操作。",
                            "Kotlin 示例尚未就绪")
                    } else {
                        FileDocumentManager.getInstance().getDocument(file)?.let(FileDocumentManager.getInstance()::saveDocument)
                        RunManager.getInstance(project).setTemporaryConfiguration(settings)
                        ProgramRunnerUtil.executeConfiguration(settings, executor)
                    }
                }
            }
        }
    }
}
