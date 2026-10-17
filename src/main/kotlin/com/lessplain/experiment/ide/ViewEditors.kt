package com.lessplain.experiment.ide

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.lessplain.experiment.browser.ViewBrowser
import com.lessplain.experiment.model.ViewRecord
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/** 文件只是宿主标识；内容和修改版本由项目存储维护。 */
class ViewVirtualFile(view: ViewRecord) : LightVirtualFile(
    (view.document.title ?: view.document.question).take(24),
    PlainTextFileType.INSTANCE, "",
) {
    val ref = view.ref
}

class ViewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is ViewVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ViewEditor(project, file as ViewVirtualFile)
    override fun getEditorTypeId() = "lessplain-react"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class ViewEditor(project: Project, private val virtualFile: ViewVirtualFile) : UserDataHolderBase(), FileEditor {
    private val host = ViewBrowser(project, virtualFile.ref)
    private val changes = PropertyChangeSupport(this)
    private var disposed = false
    init { Disposer.register(this, host) }
    override fun getComponent(): JComponent = host.component
    override fun getPreferredFocusedComponent(): JComponent = host.component
    override fun getName() = virtualFile.name
    override fun getFile(): VirtualFile = virtualFile
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = !disposed
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changes.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changes.removePropertyChangeListener(listener)
    override fun dispose() { disposed = true }
}
