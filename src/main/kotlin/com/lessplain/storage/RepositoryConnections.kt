package com.lessplain.storage

import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CancellationException

/** 每次操作使用独立 JDBC 连接，统一配置和取消边界；创建失败时也必须释放连接。 */
internal class RepositoryConnections(private val databasePath: Path) : AutoCloseable {
    @Volatile var isClosed = false
        private set

    fun open(): Connection {
        checkActive()
        // IntelliJ 的插件类加载器可能使 DriverManager 无法发现内置驱动。
        val connection = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
        }.connection
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA busy_timeout=3000")
            }
            checkActive()
            return connection
        } catch (failure: Throwable) {
            try { connection.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    private fun checkActive() {
        if (isClosed || Thread.currentThread().isInterrupted) throw CancellationException("数据库操作已取消")
    }

    override fun close() { isClosed = true }
}
