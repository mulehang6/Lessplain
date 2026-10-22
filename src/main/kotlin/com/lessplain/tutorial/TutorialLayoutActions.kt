package com.lessplain.tutorial

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.lessplain.ide.uiRead
import javax.swing.Icon

/** 与平台 Markdown 使用相同的图标、顺序和 FileEditor 标签栏入口。 */
internal class TutorialLayoutActions(view: TutorialEditorView) : DefaultActionGroup(
    LayoutAction(view, TutorialDisplayMode.EDIT, "编辑", AllIcons.General.LayoutEditorOnly),
    LayoutAction(view, TutorialDisplayMode.SPLIT, "编辑和预览", AllIcons.General.LayoutEditorPreview),
    LayoutAction(view, TutorialDisplayMode.PREVIEW, "预览", AllIcons.General.LayoutPreviewOnly),
)

private class LayoutAction(
    private val view: TutorialEditorView,
    private val mode: TutorialDisplayMode,
    title: String,
    icon: Icon,
) : ToggleAction(title, title, icon), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun isSelected(event: AnActionEvent) = view.mode == mode
    override fun setSelected(event: AnActionEvent, state: Boolean) { if (state) uiRead { view.display(mode) } }
}
