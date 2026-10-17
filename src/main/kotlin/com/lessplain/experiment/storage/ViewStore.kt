package com.lessplain.experiment.storage

import kotlinx.serialization.json.Json
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Properties

/** 项目级独立实验存储。全部读写在同一连接的串行事务中完成。 */
class ViewStore(private val file: Path) : AutoCloseable {
    private var connection: Connection? = null
    private var closed = false
    val json = Json { encodeDefaults = true }

    @Synchronized
    fun <T> read(block: Transaction.() -> T): T = Transaction(open()).block()

    @Synchronized
    fun <T> transaction(block: Transaction.() -> T): T {
        val database = open()
        check(database.autoCommit) { "不允许嵌套事务" }
        database.autoCommit = false
        try {
            val result = Transaction(database).block()
            database.commit()
            return result
        } catch (failure: Throwable) {
            database.rollback()
            throw failure
        } finally {
            database.autoCommit = true
        }
    }

    private fun open(): Connection {
        check(!closed) { "项目存储已关闭" }
        connection?.let { return it }
        Files.createDirectories(file.toAbsolutePath().parent)
        return JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), Properties()).also { database ->
            database.createStatement().use {
                it.execute("PRAGMA busy_timeout=5000")
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("CREATE TABLE IF NOT EXISTS entries (bucket TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(bucket,key))")
            }
            connection = database
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        connection?.close()
        connection = null
    }

    class Transaction(private val database: Connection) {
        fun get(bucket: String, key: String): String? =
            database.prepareStatement("SELECT value FROM entries WHERE bucket=? AND key=?").use { statement ->
                statement.setString(1, bucket)
                statement.setString(2, key)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }

        fun all(bucket: String): Map<String, String> =
            database.prepareStatement("SELECT key,value FROM entries WHERE bucket=? ORDER BY rowid").use { statement ->
                statement.setString(1, bucket)
                statement.executeQuery().use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
                }
            }

        fun put(bucket: String, key: String, value: String) {
            database.prepareStatement("INSERT INTO entries(bucket,key,value) VALUES(?,?,?) ON CONFLICT(bucket,key) DO UPDATE SET value=excluded.value").use {
                it.setString(1, bucket)
                it.setString(2, key)
                it.setString(3, value)
                it.executeUpdate()
            }
        }

        fun remove(bucket: String, key: String) {
            database.prepareStatement("DELETE FROM entries WHERE bucket=? AND key=?").use {
                it.setString(1, bucket)
                it.setString(2, key)
                it.executeUpdate()
            }
        }
    }
}
