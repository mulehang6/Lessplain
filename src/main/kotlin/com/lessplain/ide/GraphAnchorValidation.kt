package com.lessplain.ide

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.lessplain.model.UnderstandingGraph
import com.lessplain.model.UnderstandingInputException
import com.lessplain.model.mapAnchors
import com.lessplain.model.validate
import com.lessplain.storage.sha256
import java.nio.file.Path

/** 同一次读操作捕获的源码文本与符号查询，只在准备阶段使用。 */
internal data class AnchorSourceSnapshot(val content: String, val symbolAt: (Int) -> String?)

/** 不使用行号或符号回退修复新图；有任何错误就保留原存储和注释。 */
internal fun validateGraphAnchors(
    graph: UnderstandingGraph,
    snapshotFor: (String) -> AnchorSourceSnapshot?,
): UnderstandingGraph {
    val errors = mutableListOf<String>()
    val snapshots = mutableMapOf<String, AnchorSourceSnapshot?>()
    graph.nodes.filter { it.kind != "placeholder" && it.anchor == null &&
        graph.evidence.none { evidence -> evidence.id in it.evidenceIds } }.forEach {
        errors += "nodes[" + it.id + "]：缺少可定位的锚点或证据"
    }
    val prepared = graph.mapAnchors { reference, anchor ->
        try {
            // 缺省版本仅用于提交草稿；先检查路径，再访问项目文件。
            anchor.copy(version = anchor.version.ifEmpty { "pending" }).validate()
            if (!snapshots.containsKey(anchor.path)) snapshots[anchor.path] = snapshotFor(anchor.path)
            val snapshot = snapshots[anchor.path]
                ?: throw UnderstandingInputException("文件不存在或无法读取当前文档")
            val content = snapshot.content.replace("\r\n", "\n")
            val text = anchor.text.replace("\r\n", "\n")
            val offset = content.indexOf(text)
            if (offset < 0) throw UnderstandingInputException("源码片段已变化或不存在")
            if (content.indexOf(text, offset + 1) >= 0) throw UnderstandingInputException("源码片段不唯一")
            val symbolOffset = offset + text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            if (anchor.symbol != null && !symbolMatches(anchor.symbol, snapshot.symbolAt(symbolOffset))) {
                throw UnderstandingInputException("源码片段所属符号不匹配")
            }
            val version = sha256(content)
            val supplied = anchor.version.removePrefix("sha256:").lowercase()
            if (anchor.version.isNotEmpty() && supplied != version && supplied != sha256(content.replace("\n", "\r\n"))) {
                throw UnderstandingInputException("文件版本已变化，请重新核实源码")
            }
            anchor.copy(text = text, version = version, lineHint = content.take(offset).count { it == '\n' } + 1)
        } catch (failure: IllegalArgumentException) {
            errors += reference + "（" + anchor.path + "）：" + failure.message
            anchor
        }
    }
    if (errors.isNotEmpty()) throw UnderstandingInputException("锚点校验失败：\n" + errors.joinToString("\n"))
    return prepared
}

/** 在同一次读操作中校验整张图，包含 IDE 尚未保存的编辑内容。 */
internal fun prepareGraphAnchors(project: Project, graph: UnderstandingGraph): UnderstandingGraph {
    return commitSourceDocuments(project) {
        val root = Path.of(project.basePath ?: throw UnderstandingInputException("没有项目目录"))
            .toAbsolutePath().normalize()
        validateGraphAnchors(graph) { relative ->
            val path = root.resolve(relative).normalize()
            if (!path.startsWith(root)) return@validateGraphAnchors null
            val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@validateGraphAnchors null
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@validateGraphAnchors null
            val psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
            AnchorSourceSnapshot(document.text) { offset -> symbolPathAt(psi?.findElementAt(offset)) }
        }
    }
}
