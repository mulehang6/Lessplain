package com.lessplain.lesson

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ApplicationManager
import com.lessplain.ui.NativeMenuItem
import com.lessplain.ui.showNativeMenu
import com.lessplain.ide.captureEditorAnchor
import java.nio.file.Path
import javax.swing.JComponent
import java.awt.KeyboardFocusManager

/** 不要求已有图节点；动作绑定用户实际选中的编辑器与源码片段。 */
class LearnSelectedCodeAction : AnAction("学习选中内容", "为所选代码准备教材", AllIcons.General.Information), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val project = event.project
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val base = project?.basePath
        event.presentation.isEnabledAndVisible = editor != null && editor.project == project &&
            editor.selectionModel.hasSelection() && file?.fileSystem?.protocol == "file" && base != null &&
            Path.of(file.path).toAbsolutePath().normalize().startsWith(Path.of(base).toAbsolutePath().normalize())
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val anchor = captureEditorAnchor(project, editor) ?: return
        val focus = ApplicationManager.getApplication().runReadAction<LessonSourceFocus> {
            captureLessonFocus(editor.document.text, anchor.path, editor.selectionModel.selectionStart,
                editor.selectionModel.selectionEnd, anchor.symbol)
        }
        project.getService(LessonService::class.java).startLearning(anchor, focus)
    }
}

class AskLessonSelectionAction : LessonSelectionAction(true)
class NoteLessonSelectionAction : LessonSelectionAction(false)

/** 原生代码 viewer 与 IDE 原有的选区工具栏/右键菜单共用入口。 */
open class LessonSelectionAction(private val question: Boolean) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        event.presentation.isEnabledAndVisible = editor?.getUserData(lessonEditorSelectionKey) != null
    }
    override fun actionPerformed(event: AnActionEvent) {
        event.getData(CommonDataKeys.EDITOR)?.getUserData(lessonEditorSelectionKey)?.invoke(question)
    }
}

class OpenLessonsAction : AnAction("Lessplain 学习材料"), DumbAware {
    override fun actionPerformed(event: AnActionEvent) { event.project?.let { showLessonLibrary(it) } }
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = event.project != null }
}

internal fun showLessonLibrary(project: Project, candidates: List<LessonRecord>? = null, anchor: JComponent? = null) {
    val service = project.getService(LessonService::class.java)
    val trigger = anchor ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    service.background({ candidates ?: service.snapshot.lessons.sortedByDescending { it.createdAt } }) { records ->
        if (records.isEmpty()) {
            Messages.showInfoMessage(project, "选中源码后使用“学习选中内容”，即可开始并保存学习材料。", "学习材料")
        } else showNativeMenu(trigger,
            records.map { record -> NativeMenuItem(record.title) { service.openLesson(record.id) } },
            title = "本项目的学习材料", project = project)
    }
}
