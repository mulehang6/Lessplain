package com.lessplain.ide

import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.*

class SourceSweepAnimationTest {
    @Test fun `sweep uses the same linear 200ms prefix and never resumes a cancelled frame`() = SwingUtilities.invokeAndWait {
        var time = 0L
        var paints = 0
        val animation = SourceSweepAnimation({ time }, { false }) { paints++ }
        try {
            animation.start()
            assertEquals(0f, animation.progress)
            time = 100_000_000; animation.advance()
            assertEquals(0.5f, animation.progress)
            fun paint(): BufferedImage = BufferedImage(200, 30, BufferedImage.TYPE_INT_ARGB).also { image ->
                image.createGraphics().let { g -> try { paintSourceSweep(g, Rectangle(0, 0, 200, 30), animation.progress) } finally { g.dispose() } }
            }
            val half = paint()
            assertNotEquals(0, half.getRGB(40, 15))
            assertEquals(0, half.getRGB(140, 15))
            animation.start()
            assertEquals(0f, animation.progress, "快速切步替换旧动画")
            time += 200_000_000; animation.advance()
            assertEquals(1f, animation.progress)
            assertFalse(animation.isRunning)
            val full = paint()
            assertTrue((full.getRGB(40, 15) ushr 24) > (full.getRGB(140, 15) ushr 24), "底色保持从左向右渐淡")
            animation.reset(); animation.advance()
            assertEquals(0f, animation.progress)
            animation.start(); animation.finish()
            assertEquals(1f, animation.progress)
            assertFalse(animation.isRunning)
            animation.start(); animation.close()
            val before = paints
            time += 10; animation.advance(); animation.start()
            assertFalse(animation.isRunning)
            assertEquals(before, paints)
        } finally { animation.close() }
    }

    @Test fun `reduced motion shows the final highlight without creating a running timer`() = SwingUtilities.invokeAndWait {
        val animation = SourceSweepAnimation({ 0L }, { true }) { }
        try { animation.start(); assertEquals(1f, animation.progress); assertFalse(animation.isRunning) }
        finally { animation.close() }
    }
}
