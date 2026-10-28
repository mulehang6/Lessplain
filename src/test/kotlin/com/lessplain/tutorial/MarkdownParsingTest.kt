package com.lessplain.tutorial

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/** Markdown 插件的公开解析入口使用应用级服务，测试显式提供其平台生命周期。 */
abstract class MarkdownParsingTest {
    private lateinit var fixture: IdeaProjectTestFixture

    @BeforeEach fun startMarkdownPlatform() {
        fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("LessplainMarkdownParser", true).fixture
        fixture.setUp()
    }

    @AfterEach fun stopMarkdownPlatform() {
        ApplicationManager.getApplication().invokeAndWait { fixture.tearDown() }
    }
}
