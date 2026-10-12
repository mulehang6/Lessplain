package com.lessplain.ide

import com.intellij.openapi.editor.Document
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceResolutionTest {
    @Test fun `editing after background resolution invalidates the offset snapshot before UI application`() {
        var stamp = 12L
        val document = Proxy.newProxyInstance(Document::class.java.classLoader, arrayOf(Document::class.java)) { _, method, _ ->
            when (method.name) {
                "getModificationStamp" -> stamp
                else -> error("Unexpected document access: ${method.name}")
            }
        } as Document
        val resolved = SourceResolution(LocatedSource(LocationStatus.EXACT, EvidenceStatus.UNCHANGED, 3),
            document = document, modificationStamp = stamp)
        assertTrue(resolved.isCurrent())
        stamp++
        assertFalse(resolved.isCurrent())
    }
}
