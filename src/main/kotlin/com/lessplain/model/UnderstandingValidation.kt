package com.lessplain.model

class UnderstandingInputException(message: String) : IllegalArgumentException(message)

internal fun requireInput(value: Boolean, message: String) {
    if (!value) throw UnderstandingInputException(message)
}

fun SourceAnchor.validate() {
    requireInput(path.isNotBlank(), "锚点缺少项目相对路径")
    requireInput(!path.startsWith('/') && !path.startsWith('\\') && !path.contains(':') &&
        path.split('/', '\\').none { it == ".." }, "锚点路径必须位于项目内")
    requireInput(text.isNotBlank(), "锚点缺少源码文本")
    requireInput(version.isNotBlank(), "锚点缺少固定版本")
    requireInput(lineHint == null || lineHint > 0, "锚点行号必须大于零")
}

fun UnderstandingGraph.validate(validateAnchors: Boolean = true) {
    requireInput(nodes.isNotEmpty(), "图至少需要一个节点")
    requireInput(nodes.size <= 1000 && edges.size <= 3000, "图超过本批容量上限")
    validateClusters()
    val nodeIds = nodes.map { it.id }
    val edgeIds = edges.map { it.id }
    val evidenceIds = evidence.map { it.id }
    requireInput(nodeIds.all(String::isNotBlank) && nodeIds.distinct().size == nodeIds.size, "节点 ID 为空或重复")
    requireInput(edgeIds.all(String::isNotBlank) && edgeIds.distinct().size == edgeIds.size, "关系 ID 为空或重复")
    requireInput(evidenceIds.all(String::isNotBlank) && evidenceIds.distinct().size == evidenceIds.size, "证据 ID 为空或重复")
    nodes.forEach { node ->
        requireInput(node.flowRoles.all { it in setOf("start", "end") }, "节点 ${node.id} 起止角色无效")
        requireInput(node.endOutcome == null || node.endOutcome in setOf("success", "failure"), "节点 ${node.id} 结束结果无效")
        requireInput(node.endOutcome == null || "end" in node.flowRoles, "节点 ${node.id} 结束结果需要 end 角色")
        requireInput(node.label.isNotBlank(), "节点 ${node.id} 缺少标签")
        if (validateAnchors) node.anchor?.validate()
        requireInput(node.evidenceIds.all(evidenceIds::contains), "节点 ${node.id} 引用未知证据")
    }
    evidence.forEach { item ->
        if (validateAnchors) {
            item.anchor.validate()
            item.dependencies.forEach(SourceAnchor::validate)
        }
    }
    edges.forEach { edge ->
        requireInput(edge.inferred != true || !edge.coverage.isNullOrBlank(), "推断关系 ${edge.id} 需要 coverage 说明依据")
        requireInput(edge.from in nodeIds && edge.to in nodeIds, "关系 ${edge.id} 引用未知节点")
        requireInput(edge.relation in setOf("control_flow", "call", "data_flow", "dependency", "structure"), "关系 ${edge.id} 类型无效")
        requireInput(edge.evidenceIds.all(evidenceIds::contains), "关系 ${edge.id} 引用未知证据")
        if (edge.relation != "control_flow") {
            requireInput(edge.condition.isNullOrBlank(), "非控制流关系不能包含分支条件")
        }
    }
    edges.filter { it.relation == "control_flow" }.groupBy { it.from }.forEach { (from, outgoing) ->
        if (outgoing.size > 1) {
            requireInput(outgoing.all { !it.condition.isNullOrBlank() }, "分支 $from 的每条控制流关系都需条件")
        }
    }
}
