package com.master.lessplain.ide

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSymbolsTest {
    @Test
    fun `enum constants classes fields and constructors cannot masquerade as functions`() {
        for (prefix in listOf("", "@Deprecated ", "@Label(value = 1) ", "@Label(\"fun\") ", "public @Inject ", "public @ Inject ", "public ", "public class ", "String field = ")) {
            assertFalse(isFunctionDeclarationHeader(prefix, "(value)", "JAVA"), prefix)
        }
        assertFalse(isFunctionDeclarationHeader("data class ", "(value: Int)", "kotlin"))
        assertFalse(isFunctionDeclarationHeader("val handler = ", "(value: Int)", "kotlin"))
        assertFalse(isFunctionDeclarationHeader("@Label(\"fun\") val ", "(value)", "kotlin"))
    }

    @Test
    fun `Java and Kotlin methods allow comments between their name and parameter list`() {
        assertTrue(isFunctionDeclarationHeader("private suspend fun ", " /* 参数说明 */ (value: Int) {}", "kotlin"))
        assertTrue(isFunctionDeclarationHeader("fun <T> List<T>. ", "(value: T) {}", "kotlin"))
        assertTrue(isFunctionDeclarationHeader("public @Nullable String ", " // 参数说明\n (int value) {}", "JAVA"))
        assertTrue(isFunctionDeclarationHeader("public <T> java.util.List<T> ", "(T value) {}", "JAVA"))
        assertTrue(isFunctionDeclarationHeader("private String[] ", "(int value);", "JAVA"))
        assertFalse(isFunctionDeclarationHeader("/* fun */ val ", "(value)", "kotlin"))
    }
}
