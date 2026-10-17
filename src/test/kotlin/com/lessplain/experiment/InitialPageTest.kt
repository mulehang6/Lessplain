package com.lessplain.experiment

import com.lessplain.experiment.browser.initialPage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitialPageTest {
    @Test fun themeIsAvailableBeforeFrontendModule() {
        val html = "<html><head><meta charset=\"UTF-8\"></head><body><script type=\"module\" src=\"/app.js\"></script></body></html>"
        val theme = buildJsonObject { put("--background", "#1e1f22"); put("--ide-color-scheme", "dark") }
        val page = initialPage(html.toByteArray(), theme, buildJsonObject { put("title", "Lessplain") }).toString(Charsets.UTF_8)
        assertTrue(page.indexOf("--background") < page.indexOf("type=\"module\""))
        assertTrue(page.contains("#1e1f22"))
        assertTrue(page.contains("document.documentElement.style.setProperty"))
    }
    @Test fun themeValuesCannotTerminateBootstrapScript() {
        val theme = buildJsonObject { put("--ide-font", "</script><script>unexpected()</script>&") }
        val page = initialPage("<html><head></head><body></body></html>".toByteArray(), theme, buildJsonObject {}).toString(Charsets.UTF_8)
        assertFalse(page.contains("<script>unexpected()"))
        assertTrue(page.contains("\\u003c/script\\u003e"))
        assertTrue(page.contains("\\u0026"))
    }
}
