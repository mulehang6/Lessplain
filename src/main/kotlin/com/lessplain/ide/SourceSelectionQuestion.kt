package com.lessplain.ide

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.lessplain.core.UnderstandingCore
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingContext
import com.lessplain.storage.UnderstandingRepository
import com.lessplain.ui.CanvasTextDraft

/** 仅随具体编辑器的源码提示安装和清理；不读取项目全局选择。 */
internal data class SourceNodeBinding(
    val recordId: String,
    val recordRevision: Int,
    val nodeId: String,
    val label: String,
    val staticPath: List<String>,
)

internal val SOURCE_NODE_BINDING = Key.create<SourceNodeBinding>("lessplain.source.node")

internal fun selectedSourceBinding(editor: Editor): SourceNodeBinding? =
    editor.getUserData(SOURCE_NODE_BINDING)?.takeIf {
        !editor.isDisposed && editor.selectionModel.hasSelection() && !editor.selectionModel.selectedText.isNullOrBlank()
    }

/** 草稿与点击瞬间的选区、来源和路线一起保存，不受后续焦点变化影响。 */
internal class SourceSelectionQuestion(
    val binding: SourceNodeBinding,
    val anchor: SourceAnchor,
    val context: UnderstandingContext,
    val draft: CanvasTextDraft = CanvasTextDraft(""),
) {
    fun submit(repository: UnderstandingRepository, core: UnderstandingCore, text: String) {
        validateSourceSelection(repository, binding)
        core.prepareQuestion(text, context.token)
    }
}

internal fun validateSourceSelection(repository: UnderstandingRepository, binding: SourceNodeBinding) {
    val record = runCatching { repository.getRecord(binding.recordId) }.getOrNull()
    check(record != null && record.revision == binding.recordRevision &&
        record.graph.nodes.any { it.id == binding.nodeId }) {
        "来源节点已删除或记录版本已改变，请重新选择；输入和选区已保留"
    }
}

internal fun captureSourceQuestion(
    repository: UnderstandingRepository,
    core: UnderstandingCore,
    binding: SourceNodeBinding,
    anchor: SourceAnchor,
): SourceSelectionQuestion {
    validateSourceSelection(repository, binding)
    return SourceSelectionQuestion(binding, anchor,
        core.captureContext(binding.recordId, binding.nodeId, anchor, binding.staticPath))
}

/** 以来源和选区身份恢复草稿，不能用新的选区替换旧快照。 */
internal class SourceSelectionDrafts {
    private val values = mutableListOf<SourceSelectionQuestion>()

    fun restoreOrCapture(binding: SourceNodeBinding, anchor: SourceAnchor,
        capture: () -> SourceSelectionQuestion): SourceSelectionQuestion =
        values.firstOrNull { it.binding == binding && it.anchor.path == anchor.path &&
            it.anchor.version == anchor.version && it.anchor.text == anchor.text &&
            it.anchor.lineHint == anchor.lineHint } ?: capture().also { values += it }

    fun closed(question: SourceSelectionQuestion) {
        if (!question.draft.retain) values.remove(question)
    }

    fun clear() = values.clear()
}
