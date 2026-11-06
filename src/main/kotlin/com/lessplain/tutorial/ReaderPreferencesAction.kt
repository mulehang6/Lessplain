package com.lessplain.tutorial

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** 与 Agent 宿主无关的本地编辑入口，保存不发起模型请求。 */
class ReaderPreferencesAction : AnAction("讲解偏好", "编辑 Lessplain 的讲解偏好与项目背景", null), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let(::edit)
    }
    companion object { fun edit(project: Project) { ReaderPreferencesDialog(project).show() } }
}

private class ReaderPreferencesDialog(project: Project) : DialogWrapper(project) {
    private val store = ReaderPreferencesStore(project)
    private val current = store.snapshot()
    private val guidance = area(current.guidance)
    private val projectBackgroundField = area(current.projectBackground)

    init {
        title = "讲解偏好"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
        preferredSize = JBUI.size(560, 360)
        add(JLabel("可留空。开始讲解时交给 Agent；你本轮的明确要求优先。"), BorderLayout.NORTH)
        add(JPanel(GridLayout(2, 1, 0, JBUI.scale(12))).apply {
            add(section("所有项目的讲解偏好，例如先解释陌生对象，少翻译普通判断", guidance))
            add(section("仅当前项目的已知背景，例如不熟悉会话树结构", projectBackgroundField))
        }, BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = guidance

    override fun doOKAction() {
        store.save(ReaderPreferences(guidance.text, projectBackgroundField.text))
        super.doOKAction()
    }

    private fun area(value: String) = JBTextArea(value).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private fun section(label: String, field: JBTextArea) = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(JLabel(label).apply { labelFor = field }, BorderLayout.NORTH)
        add(JBScrollPane(field), BorderLayout.CENTER)
    }
}
