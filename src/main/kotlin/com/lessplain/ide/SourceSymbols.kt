package com.lessplain.ide

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager

/** 尽力生成文件内稳定的名称链，不包含文件名。 */
internal fun symbolPathAt(element: PsiElement?): String? {
    val names = mutableListOf<String>()
    var cursor = element
    while (cursor != null && cursor !is PsiFile) {
        if (cursor is PsiNamedElement) {
            cursor.name?.takeIf(String::isNotBlank)?.let(names::add)
        }
        cursor = cursor.parent
    }
    return names.takeIf { it.isNotEmpty() }?.asReversed()?.joinToString(".")
}

internal fun symbolMatches(expected: String, actual: String?): Boolean {
    val path = expected.substringBefore('(')
    return actual == path || actual?.startsWith("$path.") == true ||
        ('.' !in path && (actual?.endsWith(".$path") == true || actual?.contains(".$path.") == true))
}

/** 提交内存文档并在同一读取快照中查询 PSI；EDT 调用者必须由平台安全调度。 */
internal fun <T> commitSourceDocuments(project: Project, read: () -> T): T {
    check(!project.isDisposed) { "项目已关闭" }
    return PsiDocumentManager.getInstance(project).commitAndRunReadAction(Computable { read() })
}

/** 只找同文件的函数声明；签名无法可靠消歧时保留全部候选，不猜重载。 */
internal fun functionOffsetsForSymbol(file: PsiFile?, expected: String): List<Int> {
    if (file == null) return emptyList()
    val path = expected.substringBefore('(')
    val offsets = mutableListOf<Int>()
    file.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            ProgressManager.checkCanceled()
            if (element is PsiNameIdentifierOwner) {
                val identifier = element.nameIdentifier
                val actual = symbolPathAt(element)
                if (identifier != null && (actual == path || ('.' !in path && element.name == path))) {
                    val start = identifier.textRange.startOffset - element.textRange.startOffset
                    val end = identifier.textRange.endOffset - element.textRange.startOffset
                    val prefix = element.text.take(start)
                    val suffix = element.text.drop(end).trimStart()
                    // 公开名称接口也包含变量和枚举常量，必须进一步确认声明头。
                    if (isFunctionDeclarationHeader(prefix, suffix, element.language.id)) {
                        offsets += element.textRange.startOffset
                    }
                }
            }
            super.visitElement(element)
        }
    })
    return offsets.distinct()
}

/** 只接受明确的函数声明头；没有返回类型的 Java 构造器不参与函数回退。 */
internal fun isFunctionDeclarationHeader(prefix: String, suffix: String, language: String): Boolean {
    val comments = Regex("/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*")
    val before = comments.replace(prefix, " ").trim()
    val after = comments.replace(suffix, " ").trimStart()
    if (!after.startsWith('(')) return false
    if (Regex("\\b(class|interface|object|record|enum)\\s*$").containsMatchIn(before)) return false
    if (!language.equals("JAVA", ignoreCase = true)) {
        return Regex("\\b(?:fun\\s*(?:<[^;={}]*>\\s*)?(?:[\\w.$<>?,\\s]+\\.\\s*)?|def\\s*|function\\s*\\*?\\s*)$")
            .containsMatchIn(before)
    }
    // Java 方法名之前必须有返回类型。注解、修饰符和赋值不能单独作为类型。
    val match = Regex("(?:^|\\s)([\\p{L}_$][\\p{L}\\p{N}_$.]*(?:\\s*<[^;={}]*>)?(?:\\s*\\[\\s*])*)$")
        .find(before) ?: return false
    if (before.take(match.range.first).trimEnd().endsWith('@')) return false
    val type = match.groupValues[1]
    return type !in setOf("public", "protected", "private", "static", "final", "abstract", "native", "synchronized", "default", "strictfp")
}
