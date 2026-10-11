package com.master.lessplain.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

/** 复现普通 Swing 回调的写不安全上下文，并验证平台调度后读取未保存文档。 */
class CommitSourceDocumentsPlatformTest {
    private var fixture: CodeInsightTestFixture? = null

    @BeforeEach fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val projectFixture = factory.createLightFixtureBuilder("CommitSourceDocumentsPlatformTest").fixture
        val current = factory.createCodeInsightFixture(projectFixture)
        fixture = current
        current.setUp()
    }

    @AfterEach fun tearDown() {
        fixture?.tearDown()
        fixture = null
    }

    @Test fun `platform scheduler safely commits edited document from a Swing callback`() {
        val current = requireNotNull(fixture)
        val app = ApplicationManager.getApplication()
        val project = current.project
        val fileRef = AtomicReference<PsiFile>()
        app.invokeAndWait({ fileRef.set(current.configureByText("Snapshot.txt", "before")) }, ModalityState.nonModal())
        val file = fileRef.get()
        val manager = PsiDocumentManager.getInstance(project)
        val document = requireNotNull(manager.getDocument(file))
        val result = CompletableFuture<String>()
        val guard = TransactionGuard.getInstance()
        SwingUtilities.invokeLater {
            try {
                check(!guard.isWritingAllowed) { "测试必须从写不安全的 Swing 回调开始" }
                app.invokeLater({
                    try {
                        check(guard.isWritingAllowed) { "平台调度应提供写安全上下文" }
                        app.runWriteAction { document.setText("after") }
                        check(!manager.isCommitted(document)) { "测试需要尚未提交的文档" }
                        result.complete(commitSourceDocuments(project) {
                            check(manager.isCommitted(document)) { "读取前应提交文档" }
                            file.text
                        })
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    }
                }, ModalityState.nonModal())
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        check(!app.isDispatchThread) { "不能阻塞事件线程等待平台回调" }
        assertEquals("after", result.get(10, TimeUnit.SECONDS))
    }
}
