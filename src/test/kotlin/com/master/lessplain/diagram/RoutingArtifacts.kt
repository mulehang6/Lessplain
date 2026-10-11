package com.master.lessplain.diagram

import java.awt.Shape
import java.awt.geom.PathIterator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs

/** 矢量副本保留原始几何，缩放可超出栅格概览的分辨率。 */
internal object RoutingArtifacts {
    fun writeSvg(layout: UnderstandingGraphLayout, destination: Path) {
        val b = layout.bounds
        val svg = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"${b.x} ${b.y} ${b.width} ${b.height}\" width=\"${b.width}\" height=\"${b.height}\">")
            append("<rect x=\"${b.x}\" y=\"${b.y}\" width=\"${b.width}\" height=\"${b.height}\" fill=\"#fafbfc\"/>")
            layout.clusters.forEach { cluster ->
                val c = cluster.bounds
                append("<rect x=\"${c.x}\" y=\"${c.y}\" width=\"${c.width}\" height=\"${c.height}\" rx=\"9\" fill=\"#f5f8fc\" stroke=\"#9eacc0\" stroke-width=\"2\"/>")
                cluster.textLayout.titleLines.forEachIndexed { index, line ->
                    text(line, c.centerX, c.y + 28 + index * cluster.textLayout.titleLineHeight, bold = true)
                }
            }
            append("<g fill=\"none\" stroke=\"#596579\" stroke-width=\"1.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">")
            layout.edges.forEach { edge -> path(edge.path); path(edge.arrowPath) }
            append("</g>")
            val edges = layout.edges.associateBy { it.edge.id }
            for (crossing in layout.crossings) {
                val point = crossing.point
                val top = edges.getValue(crossing.secondEdgeId)
                val horizontal = top.segments.any { abs(it.y1 - it.y2) < 0.01 && abs(it.y1 - point.y) < 0.01 &&
                    point.x > minOf(it.x1, it.x2) && point.x < maxOf(it.x1, it.x2) }
                val color = if (layout.clusters.any { it.bounds.contains(point) }) "#f5f8fc" else "#fafbfc"
                val straight = if (horizontal) "M ${point.x - 5} ${point.y} L ${point.x + 5} ${point.y}"
                    else "M ${point.x} ${point.y - 5} L ${point.x} ${point.y + 5}"
                val arc = if (horizontal) "M ${point.x - 5} ${point.y} Q ${point.x} ${point.y - 7} ${point.x + 5} ${point.y}"
                    else "M ${point.x} ${point.y - 5} Q ${point.x + 7} ${point.y} ${point.x} ${point.y + 5}"
                append("<path d=\"$straight\" stroke=\"$color\" stroke-width=\"7\" stroke-linecap=\"round\"/>")
                append("<path d=\"$arc\" fill=\"none\" stroke=\"$color\" stroke-width=\"7\"/>")
                append("<path d=\"$arc\" fill=\"none\" stroke=\"#596579\" stroke-width=\"1.2\"/>")
            }
            layout.edges.forEach { edge -> edge.resolvedLabelBounds?.let { label ->
                append("<rect x=\"${label.x}\" y=\"${label.y}\" width=\"${label.width}\" height=\"${label.height}\" rx=\"4\" fill=\"#fafbfc\"/>")
                text(edge.edge.condition.orEmpty().take(32), label.centerX, label.centerY + 4)
            } }
            layout.nodes.values.forEach { placed ->
                val node = placed.bounds
                append("<rect x=\"${node.x}\" y=\"${node.y}\" width=\"${node.width}\" height=\"${node.height}\" rx=\"5\" fill=\"white\" stroke=\"#9eacc0\"/>")
                val lines = DiagramTextMetrics.lines(placed.node.label, (node.width - 28).toInt()).take(3)
                lines.forEachIndexed { index, line -> text(line, node.centerX,
                    node.centerY + 4 + (index - (lines.size - 1) / 2.0) * 15) }
            }
            append("</svg>")
        }
        Files.writeString(destination, svg)
    }

    fun writeGallery(metricsJson: String, destination: Path) {
        Files.writeString(destination, """
            <!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>多 cluster 连线对比</title>
            <style>
            body{font:14px system-ui;margin:0;color:#243047;background:#fafbfc}header{padding:18px 24px;background:#fff;border-bottom:1px solid #dce3ed}
            h1{font-size:20px;margin:0 0 12px}select,button,input{font:inherit;margin:0 12px 8px 0}p{margin:8px 0;color:#536078}
            table{border-collapse:collapse;margin:14px 24px}td,th{padding:6px 14px;text-align:right;border-bottom:1px solid #e1e7ee}th:first-child,td:first-child{text-align:left}
            #viewport{height:72vh;overflow:auto;border-top:1px solid #dde5ef;background:#fafbfc}img{display:block}a{color:#2563eb}
            </style><header><h1>同一张图的布局对比</h1>
            <select id="sample" aria-label="样例"><option value="article-publish">文章发布</option><option value="paid-article">付费文章</option><option value="dense-fork-join">多分支汇合与回路</option></select>
            <select id="direction" aria-label="方向"><option value="VERTICAL">纵向</option><option value="HORIZONTAL">横向</option></select>
            <select id="version" aria-label="方案"><option value="separate-lanes">新多组：独立通道</option><option value="legacy">原多组</option><option value="ordinary">单图</option><option value="regrouped">按职责重分组</option></select>
            <button id="fit">显示全图</button><label>缩放 <input id="zoom" type="range" min="0.1" max="2" step="0.05" value="1"></label>
            <a id="download" target="_blank">打开矢量图</a><p>所有方案保留原节点与原关系。拖动滚动条查看局部；矢量图放大后仍可读。交叉桥表示线路交叉，不是连接节点。</p>
            <p>付费文章的重分组合并支付协调、双渠道、确认和回调，购买入口、异步通知与独立阅读请求继续各自成组。</p></header>
            <table><thead><tr><th>方案</th><th>遮挡</th><th>贴线／标签冲突</th><th>交叉</th><th>宽 × 高</th><th>布局中位数 ms</th></tr></thead><tbody id="metrics"></tbody></table>
            <div id="viewport"><img id="graph" alt="布局对比矢量图"></div><script>
            const data=$metricsJson;
            const labels={'ordinary':'单图','legacy':'原多组','separate-lanes':'新多组','regrouped':'重分组'};
            const sample=document.getElementById('sample'),direction=document.getElementById('direction'),version=document.getElementById('version');
            const viewport=document.getElementById('viewport'),graph=document.getElementById('graph'),zoom=document.getElementById('zoom');
            let selected;
            function scale(){graph.style.width=(selected.width*Number(zoom.value))+'px';graph.style.height='auto';}
            function fit(){zoom.value=Math.max(.1,Math.min(2,Math.min(viewport.clientWidth/selected.width,viewport.clientHeight/selected.height)));scale();}
            function update(){
              const rows=data.filter(r=>r.sample===sample.value&&r.direction===direction.value);
              selected=rows.find(r=>r.version===version.value);
              const filename=selected.sample+'-'+selected.direction.toLowerCase()+'-'+selected.version+'.svg';
              graph.src=filename;document.getElementById('download').href=filename;
              const body=document.getElementById('metrics');body.replaceChildren();
              rows.forEach(r=>{const row=document.createElement('tr');[labels[r.version],r.quality.contentConflicts,r.quality.laneConflictPairs.length,r.quality.crossings,Math.round(r.width)+' × '+Math.round(r.height),r.layoutMs.toFixed(1)].forEach(value=>{const cell=document.createElement('td');cell.textContent=value;row.append(cell)});body.append(row)});
              fit();viewport.scrollTo(0,0);
            }
            [sample,direction,version].forEach(control=>control.addEventListener('change',update));
            zoom.addEventListener('input',scale);document.getElementById('fit').addEventListener('click',fit);update();
            </script></html>
        """.trimIndent())
    }

    private fun StringBuilder.text(value: String, x: Double, y: Double, bold: Boolean = false) {
        append("<text x=\"$x\" y=\"$y\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#374151\"")
        if (bold) append(" font-weight=\"bold\"")
        append(">${escape(value)}</text>")
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun StringBuilder.path(shape: Shape) {
        val iterator = shape.getPathIterator(null)
        val coordinates = DoubleArray(6)
        append("<path d=\"")
        while (!iterator.isDone) {
            val kind = iterator.currentSegment(coordinates)
            val (command, count) = when (kind) {
                PathIterator.SEG_MOVETO -> "M" to 2
                PathIterator.SEG_LINETO -> "L" to 2
                PathIterator.SEG_QUADTO -> "Q" to 4
                PathIterator.SEG_CUBICTO -> "C" to 6
                else -> "Z" to 0
            }
            append(command)
            repeat(count) { append(String.format(Locale.ROOT, " %.3f", coordinates[it])) }
            iterator.next()
        }
        append("\"/>")
    }
}
