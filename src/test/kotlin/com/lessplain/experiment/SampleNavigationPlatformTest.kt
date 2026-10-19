package com.lessplain.experiment

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.experiment.ide.SampleNavigation
import com.lessplain.experiment.ide.SampleVirtualFile
import com.lessplain.experiment.core.componentLocation
import kotlinx.serialization.json.*
import com.lessplain.experiment.model.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class SampleNavigationPlatformTest {
    @Test fun componentJsonUsesReadonlyEditorAndReleasesItsSnapshotOnClose() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(factory.createFixtureBuilder("ComponentPreview").fixture)
        fixture.setUp()
        val released = mutableListOf<String>()
        val navigation = SampleNavigation(fixture.project) { released += it }
        try {
            val spec = JsonPayload(Json.parseToJsonElement("""{"root":"data","elements":{"data":{"type":"JsonView","props":{"value":null,"label":"状态"}}}}""").jsonObject)
            val view = ViewRecord("view:component", 1, ViewDocument("结果", spec), emptyMap(), 1, 1)
            val location = componentLocation(view, "data", buildJsonObject { put("count", 2) }, JsonPayload.empty())
            ApplicationManager.getApplication().invokeAndWait { navigation.invalidate(AppSnapshot(listOf(view), emptyList(), emptyMap())) }
            runBlocking { navigation.open(location) }
            ApplicationManager.getApplication().invokeAndWait {
                val manager = FileEditorManager.getInstance(fixture.project)
                val file = manager.selectedFiles.single() as SampleVirtualFile
                assertFalse(file.isWritable)
                assertTrue(file.name.endsWith(".json"))
                assertEquals(location.sample.code, manager.selectedTextEditor!!.document.text)
                assertEquals(location.componentSnapshot, navigation.location(manager.selectedTextEditor!!)!!.componentSnapshot)
                manager.closeFile(file)
                assertEquals(listOf(location.target.id), released)
            }
        } finally {
            ApplicationManager.getApplication().invokeAndWait { Disposer.dispose(navigation) }
            fixture.tearDown()
        }
    }

    @Test fun opensReadonlyMemoryCodeReusesItAndInvalidatesReplacedSamples() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(factory.createFixtureBuilder("SampleNavigation").fixture)
        fixture.setUp()
        val navigation = SampleNavigation(fixture.project)
        try {
            val sample = CodeSample("等待", "kotlin", "fun main() {\n    println(1)\n}")
            val location = SampleLocation("view:sample", 1, CodeSampleTarget("sample", 2), sample, ViewOrigin(viewRef = "view:sample"))
            runBlocking { navigation.open(location) }
            ApplicationManager.getApplication().invokeAndWait {
                val manager = FileEditorManager.getInstance(fixture.project)
                val file = manager.selectedFiles.single() as SampleVirtualFile
                assertFalse(file.isWritable)
                assertEquals("等待.kt", file.name)
                assertEquals(sample.code, manager.selectedTextEditor?.document?.text)
                assertEquals(1, manager.selectedTextEditor?.caretModel?.logicalPosition?.line)
                assertEquals(location, navigation.location(manager.selectedTextEditor!!))
            }
            runBlocking { navigation.open(location) }
            ApplicationManager.getApplication().invokeAndWait {
                val manager = FileEditorManager.getInstance(fixture.project)
                assertEquals(1, manager.openFiles.filterIsInstance<SampleVirtualFile>().size)
                navigation.invalidate(AppSnapshot(emptyList(), emptyList(), emptyMap()))
                assertTrue(manager.openFiles.none { it is SampleVirtualFile })
            }
            assertFailsWith<IllegalStateException> { runBlocking { navigation.open(location) } }
        } finally {
            ApplicationManager.getApplication().invokeAndWait { Disposer.dispose(navigation) }
            fixture.tearDown()
        }
    }
}
