package com.lessplain.diagram;

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 短标签与文件完整显示名称分开，提示文本沿用平台默认行为。 */
public final class UnderstandingTabTitleProvider implements EditorTabTitleProvider {
    @Override
    public @Nullable String getEditorTabTitle(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof UnderstandingVirtualFile graph ? graph.getTabTitle() : null;
    }
}
