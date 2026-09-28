package com.master.understanding.ide

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement

/** A stable best-effort name chain within one file, excluding the file name. */
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
