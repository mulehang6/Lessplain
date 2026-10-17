package com.lessplain.experiment.ide

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.lessplain.experiment.model.*
import com.lessplain.ide.*
import com.lessplain.model.SourceAnchor
import com.lessplain.storage.sha256
import java.nio.file.Path

internal fun SourceAnchor.snapshot() = ResolvedSource(path, text, symbol, version, lineHint ?: 1, selectionRange)
internal fun ResolvedSource.anchor() = SourceAnchor(path, symbol, text, version, line, sha256(text), selectionRange)

/** 原生选区有确切位置；同一版本中只校验该范围，不按全文唯一性拒绝合法重复文本。 */
internal fun validateViewSelection(project: Project, selected: ResolvedSource): ResolvedSource =
    commitSourceDocuments(project) {
        val root = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
        val path = root.resolve(selected.path).normalize()
        require(!Path.of(selected.path).isAbsolute && path.startsWith(root)) { "选区路径必须在项目内" }
        val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: error("选区文件不存在；输入已保留")
        requireProjectSourcePath(root, path)
        val document = FileDocumentManager.getInstance().getDocument(file) ?: error("选区不是可读文本")
        val content = document.text
        check(sha256(content) == selected.version) { "所选源码已变化；输入已保留，请重新选择" }
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
        val range = selected.selectionRange
        if (range != null) {
            check(range.matches(content, selected.text)) { "所选范围与原文不匹配；输入已保留，请重新选择" }
            val codeOffset = range.startOffset + selected.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            check(selected.symbol == null || symbolMatches(selected.symbol, symbolPathAt(psi?.findElementAt(codeOffset)))) {
                "所选源码符号已变化；输入已保留，请重新选择"
            }
            check(document.getLineNumber(range.startOffset) + 1 == selected.line) { "选区位置已变化；输入已保留" }
        } else {
            val located = matchAnchorText(selected.anchor(), content, { offset -> symbolPathAt(psi?.findElementAt(offset)) })
            check(located.location == LocationStatus.EXACT && located.evidence == EvidenceStatus.UNCHANGED) {
                "旧选区无法唯一定位；输入已保留，请重新选择"
            }
        }
        selected
    }

/** 精确引用只在当前 IDE 文档中验证；不接受生成的行号或旧缓存文本。 */
internal fun resolveViewSources(project: Project, sources: Map<String, SourceReference>): Map<String, ResolvedSource> =
    commitSourceDocuments(project) {
        val root = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
        val errors = mutableListOf<String>()
        val resolved = sources.mapNotNull { (id, source) ->
            runCatching {
                val path = root.resolve(source.path).normalize()
                require(!Path.of(source.path).isAbsolute && path.startsWith(root)) { "路径必须在项目内" }
                val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: error("文件不存在")
                requireProjectSourcePath(root, path)
                val doc = FileDocumentManager.getInstance().getDocument(file) ?: error("不是可读文本文件")
                val psi = PsiDocumentManager.getInstance(project).getPsiFile(doc)
                val content = doc.text
                val matches = mutableListOf<Int>()
                var offset = content.indexOf(source.text)
                while (offset >= 0) {
                    val code = offset + source.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    if (source.symbol == null || symbolMatches(source.symbol, symbolPathAt(psi?.findElementAt(code)))) matches += offset
                    offset = content.indexOf(source.text, offset + 1)
                }
                require(matches.size == 1) { if (matches.isEmpty()) "源码或符号不匹配" else "源码存在多个匹配，请提供更具体片段" }
                id to ResolvedSource(root.relativize(path).toString().replace('\\', '/'), source.text, source.symbol, sha256(content), doc.getLineNumber(matches.single()) + 1)
            }.onFailure { errors += id + "：" + it.message }.getOrNull()
        }.toMap()
        require(errors.isEmpty()) { errors.joinToString("\n") }
        resolved
    }
