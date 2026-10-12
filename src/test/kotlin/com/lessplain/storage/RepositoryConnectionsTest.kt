package com.lessplain.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class RepositoryConnectionsTest {
    @TempDir lateinit var root: Path

    @Test fun `closed and interrupted owners cannot create a database connection`() {
        val database = root.resolve("cancelled.db")
        val connections = RepositoryConnections(database)
        Thread.currentThread().interrupt()
        try { assertFailsWith<CancellationException> { connections.open() } }
        finally { Thread.interrupted() }
        assertFalse(Files.exists(database))
        connections.close()
        assertFailsWith<CancellationException> { connections.open() }
        assertFalse(Files.exists(database))
    }

    @Test fun `each operation configures its own connection and repository close releases the writer lock`() {
        val repository = UnderstandingRepository(root)
        repository.connect().use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_keys").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
                statement.executeQuery("PRAGMA busy_timeout").use { rows -> rows.next(); assertEquals(3000, rows.getInt(1)) }
            }
        }
        repository.close()
        repository.close()
        assertFailsWith<CancellationException> { repository.connect() }
        UnderstandingRepository(root).use { assertFalse(it.readOnly) }
    }
}
