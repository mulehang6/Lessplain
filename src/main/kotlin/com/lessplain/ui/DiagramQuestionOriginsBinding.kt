package com.lessplain.ui

import com.lessplain.diagram.UnderstandingDiagram
import com.lessplain.ide.QuestionOriginTarget
import com.lessplain.ide.UnderstandingService
import com.lessplain.model.UnderstandingRecord

/** 标题入口与冻结问题直接绑定；切层或普通节点选择不会改写已打开的窗口目标。 */
internal fun UnderstandingDiagram.bindQuestionOrigins(service: UnderstandingService, titleRecord: UnderstandingRecord?,
    clusterSource: (String) -> SourceCluster?, editor: Boolean): AutoCloseable {
    fun open(target: QuestionOriginTarget) {
        val window = if (editor) service.editorQuestionOrigins else service.panelQuestionOrigins
        window.show(this, target)
    }
    val headerBinding = header.bindQuestion(titleRecord?.let { record -> { open(QuestionOriginTarget(record.id, record.questionId)) } })
    questionClusterIds = clusterSpecs.map { it.id }.filterTo(mutableSetOf()) { clusterSource(it) != null }
    val clusterAction: (String) -> Unit = { id -> clusterSource(id)?.let { open(QuestionOriginTarget(it.record.id, id)) } }
    originCallbacks += clusterAction
    repaint()
    return AutoCloseable { headerBinding.close(); originCallbacks -= clusterAction; questionClusterIds = emptySet() }
}
