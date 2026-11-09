package com.lessplain;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import com.lessplain.ide.UnderstandingService;
import com.lessplain.tutorial.OpenTutorialsAction;
import com.lessplain.ui.OpenUnderstandingInEditorAction;
import com.lessplain.ui.UnderstandingHistoryAction;
import com.lessplain.ui.UnderstandingPanel;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 直接装配原生组件，仅实现所需扩展方法，沿用平台其余默认行为。 */
public final class MyToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var service = project.getService(UnderstandingService.class);
        toolWindow.setTitleActions(List.of(new OpenTutorialsAction(),
                new UnderstandingHistoryAction(service), new OpenUnderstandingInEditorAction(project, service)));
        var panel = new UnderstandingPanel(service);
        var content = ContentFactory.getInstance().createContent(panel, null, false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
