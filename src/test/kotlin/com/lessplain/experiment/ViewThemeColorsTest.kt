package com.lessplain.experiment

import com.lessplain.experiment.browser.cssColor
import com.lessplain.experiment.browser.themePalette
import com.lessplain.experiment.browser.readableForeground
import org.junit.jupiter.api.Test
import java.awt.Color
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** 防止透明白色的主题令牌再次导出成刺眼的纯白。 */
class ViewThemeColorsTest {
    @Test fun cssPreservesTransparentAndOpaqueThemeColors() {
        assertEquals("rgba(255,255,255,${30 / 255.0})", cssColor(Color(255, 255, 255, 30)))
        assertEquals("rgba(255,255,255,0.0)", cssColor(Color(255, 255, 255, 0)))
        assertEquals("rgba(0,0,0,${24 / 255.0})", cssColor(Color(0, 0, 0, 24)))
        assertEquals("#1e1f22", cssColor(Color(0x1E1F22)))
    }

    @Test fun paletteSeparatesReadableTextDisabledTextAndCompositedMenuHover() {
        for ((background, foreground) in listOf(Color(0x1E1F22) to Color(0xDFE1E5), Color(0xF7F8FA) to Color(0x252932))) {
            val disabled = Color(0x555555)
            val overlay = if (background.red < 128) Color(255, 255, 255, 30) else Color(0, 0, 0, 30)
            val palette = themePalette(background, foreground, foreground, disabled, Color.GRAY, Color(0x548AF7), foreground, overlay, overlay)
            assertEquals(foreground, palette["--foreground"])
            assertEquals(disabled, palette["--disabled-foreground"])
            assertEquals(overlay, palette["--control-hover"])
            assertEquals(255, palette.getValue("--accent").alpha)
            assertNotEquals(background, palette["--accent"])
            assertNotEquals(disabled, palette["--muted-foreground"])
        }
    }

    @Test fun customThemeLowContrastTextFallsBackWithoutChangingValidNativeColors() {
        assertEquals(Color.WHITE, readableForeground(Color(0x1E1F22), Color(0x333333)))
        assertEquals(Color.BLACK, readableForeground(Color.WHITE, Color(0xEEEEEE)))
        assertEquals(Color(0xDFE1E5), readableForeground(Color(0x1E1F22), Color(0xDFE1E5)))
    }
}
