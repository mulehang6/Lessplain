package com.lessplain.experiment

import com.lessplain.experiment.browser.HeartbeatWatchdog
import com.lessplain.experiment.browser.BrowserDiagnosticEvents
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class HeartbeatWatchdogTest {
    @Test fun timelineExpiresOldEntriesAndProtectsItsClock() {
        var now = 0L
        val timeline = BrowserDiagnosticEvents(20) { now }
        timeline.record("start", mapOf("elapsedMs" to JsonPrimitive(999999)))
        now = 119_999; timeline.record("measure")
        assertEquals(2, timeline.snapshot().size)
        assertEquals(0, timeline.snapshot()[0].jsonObject.getValue("elapsedMs").jsonPrimitive.long)
        now = 120_001
        assertEquals("measure", timeline.snapshot().single().jsonObject.getValue("kind").jsonPrimitive.content)
    }
    @Test fun detectsMissingHeartbeatOnlyOnceUntilRecovery() {
        var now = 0L
        val watchdog = HeartbeatWatchdog { now }
        watchdog.receive(true)
        repeat(3) { now += 2000; assertFalse(watchdog.poll(true)) }
        now += 2000; assertTrue(watchdog.poll(true))
        now += 2000; assertFalse(watchdog.poll(true))
        watchdog.receive(true)
        repeat(3) { now += 2000; assertFalse(watchdog.poll(true)) }
        now += 2000; assertTrue(watchdog.poll(true))
    }
    @Test fun hiddenUninitializedAndSleepResumeDoNotProduceFalseAlarms() {
        var now = 0L
        val watchdog = HeartbeatWatchdog { now }
        repeat(10) { now += 2000; assertFalse(watchdog.poll(true)) }
        watchdog.receive(true)
        repeat(10) { now += 2000; assertFalse(watchdog.poll(false)) }
        now += 2000; assertFalse(watchdog.poll(true))
        now += 120_000; assertFalse(watchdog.poll(true))
        now += 2000; assertFalse(watchdog.poll(true))
        watchdog.receive(false)
        repeat(10) { now += 2000; assertFalse(watchdog.poll(true)) }
    }
}
