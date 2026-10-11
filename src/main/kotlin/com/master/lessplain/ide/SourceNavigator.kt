package com.master.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.master.lessplain.model.SourceAnchor
import com.master.lessplain.storage.sha256
import java.nio.file.Path

enum class LocationStatus { EXACT, RELOCATED, UNRESOLVED, AMBIGUOUS }
enum class EvidenceStatus { UNCHANGED, CHANGED, UNKNOWN }

data class LocatedSource(
    val location: LocationStatus,
    val evidence: EvidenceStatus,
    val offset: Int? = null,
)

/** 重复文本匹配不能作为锚点已移动的证明。 */
internal fun locateSource(project: Project, anchor: SourceAnchor): LocatedSource {
    return commitSourceDocuments(project) {
        val root = Path.of(project.basePath ?: return@commitSourceDocuments LocatedSource(LocationStatus.UNRESOLVED, EvidenceStatus.UNKNOWN))
            .toAbsolutePath().normalize()
        val path = root.resolve(anchor.path).normalize()
        if (!path.startsWith(root)) return@commitSourceDocuments LocatedSource(LocationStatus.UNRESOLVED, EvidenceStatus.UNKNOWN)
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)
            ?: return@commitSourceDocuments LocatedSource(LocationStatus.UNRESOLVED, EvidenceStatus.UNKNOWN)
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return@commitSourceDocuments LocatedSource(LocationStatus.UNRESOLVED, EvidenceStatus.UNKNOWN)
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
        matchAnchorText(anchor, document.text, { offset -> symbolPathAt(psi?.findElementAt(offset)) },
            { symbol -> functionOffsetsForSymbol(psi, symbol) })
    }
}

/** 其他符号中的文本匹配仅是候选，不能据此确认迁移。 */
internal fun matchAnchorText(anchor: SourceAnchor, content: String, symbolAt: (Int) -> String?): LocatedSource =
    matchAnchorText(anchor, content, symbolAt) { emptyList() }

internal fun matchAnchorText(
    anchor: SourceAnchor,
    content: String,
    symbolAt: (Int) -> String?,
    functionOffsets: (String) -> List<Int>,
): LocatedSource {
    val expectedVersion = anchor.version.removePrefix("sha256:").lowercase()
    val normalizedContent = content.replace("\r\n", "\n")
    val versionMatches = sha256(normalizedContent) == expectedVersion ||
        sha256(normalizedContent.replace("\n", "\r\n")) == expectedVersion
    val expectedOffset = anchor.lineHint?.let { line ->
        if (line < 1) null else {
            val starts = mutableListOf(0)
            content.forEachIndexed { index, char -> if (char == '\n') starts += index + 1 }
            starts.getOrNull(line - 1)
        }
    }
    val matches = mutableListOf<Int>()
    var index = content.indexOf(anchor.text)
    while (index >= 0) {
        matches += index
        index = content.indexOf(anchor.text, index + 1)
    }
    if (versionMatches && expectedOffset != null) {
        val lineEnd = content.indexOf('\n', expectedOffset).takeIf { it >= 0 } ?: content.length
        val onHintLine = matches.filter { it in expectedOffset..lineEnd }
        if (onHintLine.size > 1) return LocatedSource(LocationStatus.AMBIGUOUS, EvidenceStatus.UNKNOWN)
        if (onHintLine.size == 1) {
            val match = onHintLine.single()
            val codeOffset = match + anchor.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            if (anchor.symbol != null && !symbolMatches(anchor.symbol, symbolAt(codeOffset))) {
                return LocatedSource(LocationStatus.UNRESOLVED, EvidenceStatus.UNKNOWN)
            }
            return LocatedSource(LocationStatus.EXACT, EvidenceStatus.UNCHANGED, match)
        }
    }
    if (matches.isEmpty()) {
        val candidates = anchor.symbol?.let(functionOffsets).orEmpty().distinct().filter { it in content.indices }
        return when (candidates.size) {
            1 -> LocatedSource(LocationStatus.RELOCATED, EvidenceStatus.CHANGED, candidates.single())
            0 -> LocatedSource(LocationStatus.UNRESOLVED, EvidenceStatus.CHANGED)
            else -> LocatedSource(LocationStatus.AMBIGUOUS, EvidenceStatus.CHANGED)
        }
    }
    if (matches.size > 1) return LocatedSource(LocationStatus.AMBIGUOUS, EvidenceStatus.UNKNOWN)
    val match = matches.single()
    // 缩进对应的 PSI 可能属于外层类，而非方法声明。
    val symbolOffset = match + anchor.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    if (anchor.symbol != null && !symbolMatches(anchor.symbol, symbolAt(symbolOffset))) {
        return LocatedSource(LocationStatus.UNRESOLVED, EvidenceStatus.UNKNOWN)
    }
    return LocatedSource(if (versionMatches) LocationStatus.EXACT else LocationStatus.RELOCATED,
        if (versionMatches) EvidenceStatus.UNCHANGED else EvidenceStatus.UNKNOWN, matches.single())
}

internal fun navigateToSource(
    project: Project,
    anchor: SourceAnchor,
    animation: SourceScrollAnimation,
    located: LocatedSource = locateSource(project, anchor),
): LocatedSource {
    val offset = located.offset ?: return located
    val root = Path.of(project.basePath ?: return located).toAbsolutePath().normalize()
    val file = ApplicationManager.getApplication().runReadAction<VirtualFile?> {
        LocalFileSystem.getInstance().findFileByNioFile(root.resolve(anchor.path).normalize())
    } ?: return located
    val editor = FileEditorManager.getInstance(project).selectedTextEditor
    if (editor != null && !editor.isDisposed && FileDocumentManager.getInstance().getFile(editor.document) == file) {
        animation.moveTo(editor, offset)
        return located
    }
    animation.cancel()
    OpenFileDescriptor(project, file, offset).navigate(true)
    return located
}
