package com.lessplain.tutorial

import org.junit.jupiter.api.Test
import kotlin.test.*

class TutorialSimulationTest {
    private val resource = TutorialSimulation("demo", "kotlin", "println(1)\nprintln(2)", listOf(
        SimulationScenario("a", "有输出", listOf(SimulationStep(listOf(1), "一", "1\n"), SimulationStep(listOf(2), "二", "2\n"))),
        SimulationScenario("b", "无输出", listOf(SimulationStep(listOf(1, 2), "跳过"))),
    ))
    @Test fun `previous reset scenario change and resource updates rebuild output from prefix`() {
        val player = SimulationPlayback(resource)
        assertEquals("", player.output)
        player.next(); assertEquals("1\n", player.output)
        player.next(); player.next(); assertEquals("1\n2\n", player.output)
        player.previous(); assertEquals("1\n", player.output)
        player.next(); assertEquals("1\n2\n", player.output)
        player.reset(); assertEquals(-1, player.stepIndex); assertEquals("", player.output)
        player.select(1); player.next(); assertEquals("", player.output)
        player.update(resource.copy(revision = 2)); assertEquals(0, player.scenarioIndex); assertEquals(-1, player.stepIndex)
        assertTrue(player.hasTerminal)
    }
    @Test fun `line errors contain a stable field and pure flows have no terminal`() {
        val bad = resource.copy(scenarios = listOf(SimulationScenario("bad", "坏行号", listOf(SimulationStep(listOf(0, 3), "错误")))))
        val error = assertFailsWith<TutorialProblem> { validateSimulation(bad) }
        assertEquals("INVALID_LINE", error.code)
        assertEquals("scenarios[0].steps[0].highlightLines", error.field)
        assertFalse(SimulationPlayback(resource.copy(scenarios = listOf(resource.scenarios[1]))).hasTerminal)
    }
}
