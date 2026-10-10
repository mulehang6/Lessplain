package com.master.lessplain.diagram

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Keeps tabs compact while retaining the complete question in the tab tooltip. */
class UnderstandingTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? UnderstandingVirtualFile)?.name

    override fun getEditorTabTooltipText(project: Project, virtualFile: VirtualFile): String? =
        (virtualFile as? UnderstandingVirtualFile)?.fullTitle
}
