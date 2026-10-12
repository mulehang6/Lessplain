package com.lessplain.ide

import com.lessplain.core.BackgroundTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnderstandingStorageTest {
    @TempDir lateinit var root: Path

    @Test fun `UI construction performs no database work and repository access is restricted to background`() {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tasks = BackgroundTasks(parent) { SwingUtilities.invokeLater(it) }
        val ready = CountDownLatch(1)
        lateinit var storage: UnderstandingStorage
        try {
            SwingUtilities.invokeAndWait {
                storage = UnderstandingStorage(root, tasks, { ready.countDown() }, { throw AssertionError(it) })
                assertFalse(Files.exists(root.resolve(".idea/lessplain/understanding.db")))
                assertFailsWith<IllegalStateException> { storage.repository }
                storage.initialize()
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            assertTrue(Files.exists(root.resolve(".idea/lessplain/understanding.db")))
            SwingUtilities.invokeAndWait { assertFalse(storage.readOnly); storage.close() }
        } finally { tasks.close(); parent.cancel() }
    }
}
