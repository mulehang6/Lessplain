package com.master.lessplain.diagram

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** 保持标签页标题紧凑，并在标签页提示中保留完整问题。 */
class UnderstandingTabTitleProvider : EditorTabTitleProvider {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? UnderstandingVirtualFile)?.name

    override fun getEditorTabTooltipText(project: Project, virtualFile: VirtualFile): String? =
        (virtualFile as? UnderstandingVirtualFile)?.fullTitle
}
