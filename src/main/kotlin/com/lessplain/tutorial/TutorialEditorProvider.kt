package com.lessplain.tutorial

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.pom.Navigatable
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

class TutorialVirtualFile(val tutorialId: String, title: String) : LightVirtualFile(title, PlainTextFileType.INSTANCE, "")

class TutorialEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is TutorialVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = TutorialFileEditor(project, file as TutorialVirtualFile)
    override fun getEditorTypeId() = "lessplain-tutorial"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class TutorialFileEditor(project: Project, private val virtualFile: TutorialVirtualFile) : UserDataHolderBase(), TextEditor {
    private val view = com.lessplain.ide.uiRead { TutorialEditorView(project, virtualFile.tutorialId) }
    private val changes = PropertyChangeSupport(this)
    private var disposed = false
    override fun getComponent() = view
    override fun getPreferredFocusedComponent() = view.focusTarget
    override fun getName() = virtualFile.name
    override fun getFile(): VirtualFile = virtualFile
    override fun getEditor() = view.editor
    override fun getTabActions() = view.tabActions
    override fun canNavigateTo(navigatable: Navigatable) = false
    override fun navigateTo(navigatable: Navigatable) = Unit
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false // 每次 Document 修改立即进入自动保存队列。
    override fun isValid() = !disposed
    override fun selectNotify() { view.active(true) }
    override fun deselectNotify() { view.active(false) }
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changes.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changes.removePropertyChangeListener(listener)
    override fun dispose() { disposed = true; com.lessplain.ide.uiRead { view.close() } }
}
