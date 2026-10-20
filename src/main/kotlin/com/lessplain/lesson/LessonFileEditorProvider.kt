package com.lessplain.lesson

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/** 标签页只持有教材身份；个人笔记和当前回答由项目服务读取。 */
class LessonVirtualFile(val lessonId: String, title: String) : LightVirtualFile(title.take(24), PlainTextFileType.INSTANCE, "")

class LessonFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is LessonVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = LessonFileEditor(project, file as LessonVirtualFile)
    override fun getEditorTypeId(): String = "lessplain-lesson"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class LessonTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? LessonVirtualFile)?.let { lesson ->
            project.getService(LessonService::class.java).snapshot.lessons.firstOrNull { it.id == lesson.lessonId }?.title?.take(24)
        }
}

private class LessonFileEditor(project: Project, private val file: LessonVirtualFile) : UserDataHolderBase(), FileEditor {
    private val view = LessonView(project, file.lessonId)
    private val changes = PropertyChangeSupport(this)
    private var disposed = false
    override fun getComponent(): JComponent = view
    override fun getPreferredFocusedComponent(): JComponent = view
    override fun getName(): String = file.name
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !disposed
    override fun selectNotify() { view.activated() }
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changes.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changes.removePropertyChangeListener(listener)
    override fun dispose() { disposed = true; view.close() }
}
