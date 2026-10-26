package com.lessplain.tutorial

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.lessplain.ui.QuestionBubbleIcon
import com.lessplain.ui.PopupUi
import com.lessplain.ui.popupIconButton
import com.intellij.util.ui.JBUI
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.ui.awt.RelativePoint
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

internal val tutorialActionsKey = Key.create<TutorialSelectionActions>("lessplain.tutorial.annotations")

/** 两个表面的选区共用入口；右键只使用所属表面的原生菜单。 */
internal class TutorialSelectionActions(private val service: TutorialService, private val id: String, private val windows: TutorialAnnotationWindows,
    private val present: (JBPopup, JComponent, Point) -> Unit = { popup, component, point -> popup.show(RelativePoint(component, point)) },
) : AutoCloseable {
    var selection: IntRange? = null
        private set
    private var popup: JBPopup? = null
    private var pending: (() -> Unit)? = null
    private var dragging = false
    private var active = true
    private var menuSuspended = false
    private var owner: JComponent? = null
    private var target: FloatTarget? = null
    private val lifetime = TutorialFloatLifetime(::dismiss)
    private data class FloatTarget(val range: IntRange, val existing: Boolean, val revision: Int, val ids: List<String>)
    private val timer = Timer(140) { selectionSettled() }.apply { isRepeats = false }

    internal fun selectionSettled() { if (!dragging && !menuActive()) pending?.invoke() }
    fun beginSelection() { dragging = true; menuSuspended = false; dismiss() }
    fun endSelection() { dragging = false; timer.restart() }
    fun changed(component: JComponent, range: IntRange?, point: Point) {
        selection = range?.takeUnless { it.isEmpty() }
        if (selection == null) { pending = null; dismiss(); return }
        pending = { display(component, range!!, point, false) }
        timer.restart()
    }
    fun suspendForMenu() { dismiss(); menuSuspended = true }
    private fun menuActive(): Boolean {
        if (menuSuspended && !JBPopupFactory.getInstance().isPopupActive) menuSuspended = false
        return menuSuspended
    }
    fun leave(component: JComponent) { if (owner === component) lifetime.text(false) }
    fun hover(component: JComponent, source: Int, point: Point) {
        if (selection != null || dragging || menuActive()) return
        val groups = annotationsAt(service.store.annotations(id), source)
        val item = groups.values.flatten().minByOrNull { it.end - it.start } ?: run { leave(component); return }
        display(component, item.start until item.end, point, true, groups)
    }
    fun create(kind: String) {
        selection?.let { windows.create(it.first, it.last + 1, kind) }
        dismiss()
    }
    fun index(component: JComponent) = windows.index(component)
    fun sources(component: JComponent) = com.lessplain.ui.showNativeMenu(component, service.store.record(id).sourceIds.map { sourceId ->
        val source = service.store.source(sourceId)
        com.lessplain.ui.NativeMenuItem("${source.path} · ${source.focus.take(50)}") { service.attempt { service.sources.reveal(sourceId) } }
    }, project = service.project)

    private fun display(component: JComponent, range: IntRange, point: Point, existing: Boolean, groups: Map<String, List<TutorialAnnotation>>? = null) {
        if (!active || !component.isShowing) return
        val revision = service.store.record(id).revision
        val items = service.store.annotations(id).filter { !it.isEmptyDraft && !it.orphaned && it.start == range.first && it.end == range.last + 1 }
        val next = FloatTarget(range, existing, revision, (groups?.values?.flatten() ?: items).map { it.id })
        if (popup != null && target == next && owner === component) { lifetime.text(true); return }
        dismiss()
        target = next; owner = component
        lifetime.shown(if (existing) AnnotationFloatOrigin.HOVER else AnnotationFloatOrigin.SELECTION)
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(PopupUi.ICON_GAP), JBUI.scale(PopupUi.ICON_GAP)))
        val mouse = object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(event: java.awt.event.MouseEvent) = lifetime.bar(true)
            override fun mouseExited(event: java.awt.event.MouseEvent) = lifetime.bar(false)
        }
        panel.addMouseListener(mouse)
        listOf("question", "note").forEach { kind ->
            val matching = groups?.get(kind).orEmpty().ifEmpty { items.filter { it.kind == kind } }
            val label = if (kind == "note") "笔记" else "追问"
            val description = if (matching.isNotEmpty()) "打开${matching.size}条$label" else "为选区添加$label"
            panel.add(popupIconButton(if (kind == "note") AllIcons.Actions.Edit else QuestionBubbleIcon, description, matching.size) {
                dismiss()
                service.attempt {
                    tutorialRequire(service.store.record(id).revision == revision, "SELECTION_STALE", "selection", "正文已更新，请重新选择")
                    if (matching.isNotEmpty()) windows.open(matching.first()) else windows.create(range.first, range.last + 1, kind)
                }
            }.apply { addMouseListener(mouse) })
        }
        val shown = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null).setFocusable(false).setRequestFocus(false)
            .setCancelOnClickOutside(false).setCancelOnOtherWindowOpen(true).createPopup()
        popup = shown
        shown.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) { if (popup === shown) { popup = null; lifetime.clear(); target = null; owner = null } }
        })
        present(shown, component, point)
    }
    fun active(value: Boolean) { active = value; if (!value) dismiss() }
    fun dismiss() {
        pending = null; timer.stop(); lifetime.clear()
        val previous = popup
        popup = null; target = null; owner = null
        previous?.cancel()
    }
    override fun close() { pending = null; dismiss() }
}

/** 使用标准 EditorPopupMenu 注册，避免与平台菜单同时弹出。 */
abstract class TutorialAnnotationAction(private val kind: String, title: String) : AnAction(title), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(event: AnActionEvent) {
        val actions = event.getData(CommonDataKeys.EDITOR)?.getUserData(tutorialActionsKey)
        event.presentation.isEnabledAndVisible = actions?.selection != null
    }
    override fun actionPerformed(event: AnActionEvent) { event.getData(CommonDataKeys.EDITOR)?.getUserData(tutorialActionsKey)?.create(kind) }
}
class TutorialNoteAction : TutorialAnnotationAction("note", "写笔记")
class TutorialQuestionAction : TutorialAnnotationAction("question", "追问此处")

/** 不依赖选区的教程导航也并入平台菜单，不占正文工具栏。 */
abstract class TutorialNavigationAction(title: String, private val source: Boolean) : AnAction(title), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.getData(CommonDataKeys.EDITOR)?.getUserData(tutorialActionsKey) != null
    }
    override fun actionPerformed(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val actions = editor.getUserData(tutorialActionsKey) ?: return
        if (source) actions.sources(editor.contentComponent) else actions.index(editor.contentComponent)
    }
}
class TutorialIndexAction : TutorialNavigationAction("批注索引", false)
class TutorialSourcesAction : TutorialNavigationAction("关联源码", true)
