package com.lessplain.diagram

import com.intellij.util.ui.JBUI
import com.lessplain.model.GraphEdge
import com.lessplain.model.SourceAnchor
import com.lessplain.model.UnderstandingGraph
import java.awt.FontMetrics

internal fun edgeRelationship(edge: GraphEdge, graph: UnderstandingGraph): String {
    val relation = when (edge.relation) {
        "control_flow" -> "执行顺序"
        "call" -> "调用"
        "data_flow" -> "数据传递"
        "dependency" -> "依赖"
        "structure" -> "包含"
        else -> "关系"
    }
    fun label(id: String) = graph.nodes.firstOrNull { it.id == id }?.label ?: id
    return "$relation：${label(edge.from)} → ${label(edge.to)}"
}

/** 正文最多三行，按界面字体测量；图缩放不会放大文字和弹层。 */
internal fun edgeTooltip(edge: GraphEdge, graph: UnderstandingGraph, metrics: FontMetrics): String {
    val summary = edge.summary?.takeIf(String::isNotBlank) ?: edgeRelationship(edge, graph)
    val condition = edge.condition?.takeIf(String::isNotBlank)
    val width = JBUI.scale(340)
    val lines = boundedTooltipLines(summary, width, metrics, if (condition == null) 3 else 2) +
        (condition?.let { boundedTooltipLines("条件：$it", width, metrics, 1) } ?: emptyList())
    val marker = if (edge.inferred == true) "<b>推断</b><br>" else ""
    return "<html>" + marker + lines.joinToString("<br>", transform = ::escapeEdgeHtml) + "</html>"
}

/** 逐个 Unicode 码点测量，避免长标识符或代理对撑破边界。 */
internal fun boundedTooltipLines(text: String, width: Int, metrics: FontMetrics, maxLines: Int = 3): List<String> {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    val points = normalized.codePoints().toArray()
    val lines = mutableListOf<String>()
    var offset = 0
    while (offset < points.size && lines.size < maxLines) {
        val line = StringBuilder()
        while (offset < points.size) {
            val next = String(Character.toChars(points[offset]))
            val remaining = offset + 1 < points.size
            val suffix = if (lines.size == maxLines - 1 && remaining) "…" else ""
            if (metrics.stringWidth(line.toString() + next + suffix) > width && line.isNotEmpty()) break
            line.append(next)
            offset++
        }
        if (lines.size == maxLines - 1 && offset < points.size) line.append("…")
        lines += line.toString()
    }
    return lines.ifEmpty { listOf("") }
}

private fun escapeEdgeHtml(text: String): String = text.replace("&", "&amp;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** 完整依据只在用户主动打开详情时展开，源数据不做截断。 */
internal fun edgeEvidenceText(edge: GraphEdge, graph: UnderstandingGraph): String = buildString {
    appendLine(edgeRelationship(edge, graph))
    if (edge.inferred == true) appendLine("推断")
    edge.summary?.takeIf(String::isNotBlank)?.let { appendLine(); appendLine(it) }
    edge.condition?.takeIf(String::isNotBlank)?.let { appendLine(); appendLine("条件：$it") }
    edge.coverage?.takeIf(String::isNotBlank)?.let { appendLine(); appendLine("完整说明"); appendLine(it) }
    val evidence = graph.evidence.filter { it.id in edge.evidenceIds }
    evidence.forEach {
        appendLine(); appendLine("关联证据：${it.id}")
        appendAnchor(it.anchor)
        it.note?.let { note -> appendLine(note) }
        it.dependencies.forEach { dependency -> appendLine("依赖证据"); appendAnchor(dependency) }
    }
    if (evidence.isEmpty() && edge.coverage.isNullOrBlank()) {
        appendLine(); appendLine("没有附加源码依据")
    }
}

private fun StringBuilder.appendAnchor(anchor: SourceAnchor) {
    appendLine(anchor.path + (anchor.symbol?.let { " / $it" } ?: ""))
    appendLine(anchor.text)
}
