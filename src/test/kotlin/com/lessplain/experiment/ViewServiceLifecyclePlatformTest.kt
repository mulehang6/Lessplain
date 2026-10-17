package com.lessplain.experiment

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.lessplain.experiment.ide.ViewService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 项目服务销毁必须连同 EditorFactory 监听器的 Disposer 子树一起释放。 */
class ViewServiceLifecyclePlatformTest {
    @Test fun disposingServiceDisposesSourceNavigationTree() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(factory.createFixtureBuilder("ViewServiceLifecycle").fixture)
        fixture.setUp()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = ViewService(fixture.project, scope)
            try {
                assertFalse(Disposer.isDisposed(service.sources))
            } finally { Disposer.dispose(service) }
            assertTrue(Disposer.isDisposed(service.sources))
        } finally {
            scope.cancel()
            fixture.tearDown()
        }
    }
}
