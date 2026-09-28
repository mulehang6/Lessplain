package com.master.understanding.storage

import com.google.gson.Gson
import com.master.understanding.model.BrowseState
import com.master.understanding.model.UnderstandingAnswer
import com.master.understanding.model.UnderstandingContext
import com.master.understanding.model.UnderstandingInputException
import com.master.understanding.model.UnderstandingQuestion
import com.master.understanding.model.UnderstandingRecord
import com.master.understanding.model.UNDERSTANDING_SCHEMA_VERSION
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import org.sqlite.SQLiteDataSource

/** All writes, including request deduplication, use one SQLite transaction. */
class UnderstandingRepository(
    projectRoot: Path,
    private val gson: Gson = Gson(),
) : AutoCloseable {
    val projectId: String = sha256(projectRoot.toAbsolutePath().normalize().toString().lowercase())
    val databasePath: Path = projectRoot.resolve(".idea/code-understanding/understanding.db")
    private val lockChannel: FileChannel
    private val writerLock: FileLock?
    val readOnly: Boolean

    init {
        Files.createDirectories(databasePath.parent)
        lockChannel = FileChannel.open(
            databasePath.parent.resolve("writer.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        writerLock = try {
            lockChannel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        readOnly = writerLock == null
        try {
            if (readOnly) {
                if (!Files.exists(databasePath)) throw IllegalStateException("数据库不存在，无法以只读模式打开")
            } else {
                migrate()
            }
        } catch (failure: Exception) {
            writerLock?.release()
            lockChannel.close()
            throw failure
        }
    }

    // IntelliJ loads bundled plugin libraries with a plugin classloader, which DriverManager may not discover.
    private fun connect(): Connection = SQLiteDataSource().apply {
        url = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
    }.connection.apply {
        createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=3000")
        }
    }

    private fun migrate() {
        val existing = Files.exists(databasePath) && Files.size(databasePath) > 0
        connect().use { db ->
            val version = db.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            }
            if (version > UNDERSTANDING_SCHEMA_VERSION) throw IllegalStateException("数据库版本较新：$version")
            if (version == UNDERSTANDING_SCHEMA_VERSION) return
            if (existing) {
                val backup = databasePath.resolveSibling("understanding.db.before-v$version-${System.currentTimeMillis()}.bak")
                db.createStatement().use { statement ->
                    statement.execute("VACUUM INTO '${backup.toAbsolutePath().toString().replace("'", "''")}'")
                }
            }
            db.autoCommit = false
            try {
                db.createStatement().use { statement ->
                    statement.execute("CREATE TABLE IF NOT EXISTS contexts (token TEXT PRIMARY KEY, project_id TEXT NOT NULL, data TEXT NOT NULL)")
                    statement.execute("CREATE TABLE IF NOT EXISTS questions (id TEXT PRIMARY KEY, context_token TEXT NOT NULL REFERENCES contexts(token), owner_record_id TEXT, text TEXT NOT NULL, status TEXT NOT NULL, data TEXT NOT NULL)")
                    statement.execute("CREATE TABLE IF NOT EXISTS records (id TEXT PRIMARY KEY, parent_id TEXT, current_revision INTEGER NOT NULL, created_at INTEGER NOT NULL)")
                    statement.execute("CREATE TABLE IF NOT EXISTS record_versions (record_id TEXT NOT NULL REFERENCES records(id), revision INTEGER NOT NULL, question_text TEXT NOT NULL, answer_text TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(record_id, revision))")
                    statement.execute("CREATE TABLE IF NOT EXISTS answers (id TEXT PRIMARY KEY, question_id TEXT NOT NULL REFERENCES questions(id), answer_text TEXT NOT NULL, data TEXT NOT NULL)")
                    statement.execute("CREATE TABLE IF NOT EXISTS requests (request_id TEXT PRIMARY KEY, operation TEXT NOT NULL, payload_hash TEXT NOT NULL, response TEXT NOT NULL)")
                    statement.execute("CREATE TABLE IF NOT EXISTS browse_state (record_id TEXT PRIMARY KEY REFERENCES records(id), data TEXT NOT NULL)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_records_created ON records(created_at DESC)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_answers_question ON answers(question_id)")
                    verifySchema(db)
                    statement.execute("PRAGMA user_version=$UNDERSTANDING_SCHEMA_VERSION")
                }
                db.commit()
            } catch (failure: Exception) {
                db.rollback()
                throw failure
            }
        }
    }

    private fun verifySchema(db: Connection) {
        val expected = mapOf(
            "contexts" to setOf("token", "project_id", "data"),
            "questions" to setOf("id", "context_token", "owner_record_id", "text", "status", "data"),
            "records" to setOf("id", "parent_id", "current_revision", "created_at"),
            "record_versions" to setOf("record_id", "revision", "question_text", "answer_text", "data"),
            "answers" to setOf("id", "question_id", "answer_text", "data"),
            "requests" to setOf("request_id", "operation", "payload_hash", "response"),
            "browse_state" to setOf("record_id", "data"),
        )
        expected.forEach { (table, columns) ->
            val actual = db.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString("name")) }
                }
            }
            if (!actual.containsAll(columns)) throw IllegalStateException("数据库迁移失败：$table 结构不兼容")
        }
    }

    private fun <T> write(block: (Connection) -> T): T {
        if (readOnly) throw IllegalStateException("另一个 IDE 实例已打开此项目，当前实例只读")
        return connect().use { db ->
            db.autoCommit = false
            try {
                val result = block(db)
                db.commit()
                result
            } catch (failure: Exception) {
                db.rollback()
                throw failure
            }
        }
    }

    fun putContext(context: UnderstandingContext) {
        if (context.projectId != projectId) throw UnderstandingInputException("上下文属于其他项目")
        write { db ->
            db.prepareStatement("INSERT INTO contexts(token, project_id, data) VALUES(?, ?, ?)").use { statement ->
                statement.setString(1, context.token)
                statement.setString(2, projectId)
                statement.setString(3, gson.toJson(context))
                statement.executeUpdate()
            }
        }
    }

    fun getContext(token: String): UnderstandingContext = connect().use { db ->
        db.prepareStatement("SELECT project_id, data FROM contexts WHERE token=?").use { statement ->
            statement.setString(1, token)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw UnderstandingInputException("contextToken 不存在或已失效")
                if (rows.getString(1) != projectId) throw UnderstandingInputException("contextToken 属于其他项目")
                gson.fromJson(rows.getString(2), UnderstandingContext::class.java)
            }
        }
    }

    fun saveQuestion(question: UnderstandingQuestion) {
        getContext(question.contextToken)
        write { db -> insertQuestion(db, question) }
    }

    fun insertQuestion(db: Connection, question: UnderstandingQuestion) {
        db.prepareStatement("INSERT INTO questions(id, context_token, owner_record_id, text, status, data) VALUES(?, ?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, question.id)
            statement.setString(2, question.contextToken)
            statement.setString(3, question.ownerRecordId)
            statement.setString(4, question.text)
            statement.setString(5, question.status)
            statement.setString(6, gson.toJson(question))
            statement.executeUpdate()
        }
    }

    fun getQuestion(id: String): UnderstandingQuestion = connect().use { db ->
        db.prepareStatement("SELECT data, owner_record_id, status FROM questions WHERE id=?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw UnderstandingInputException("questionId 不存在")
                gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java).copy(
                    ownerRecordId = rows.getString(2),
                    status = rows.getString(3),
                )
            }
        }
    }

    fun request(requestId: String, operation: String, payload: String, block: (Connection) -> String): String {
        if (requestId.isBlank()) throw UnderstandingInputException("requestId 不能为空")
        val hash = sha256(payload)
        lookupRequest(requestId, operation, hash)?.let { return it }
        return write { db ->
            db.prepareStatement("SELECT operation, payload_hash, response FROM requests WHERE request_id=?").use { statement ->
                statement.setString(1, requestId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        if (rows.getString(1) != operation || rows.getString(2) != hash) {
                            throw UnderstandingInputException("requestId 已用于不同内容")
                        }
                        return@write rows.getString(3)
                    }
                }
            }
            val result = block(db)
            db.prepareStatement("INSERT INTO requests(request_id, operation, payload_hash, response) VALUES(?, ?, ?, ?)").use { statement ->
                statement.setString(1, requestId)
                statement.setString(2, operation)
                statement.setString(3, hash)
                statement.setString(4, result)
                statement.executeUpdate()
            }
            result
        }
    }

    private fun lookupRequest(requestId: String, operation: String, hash: String): String? = connect().use { db ->
        db.prepareStatement("SELECT operation, payload_hash, response FROM requests WHERE request_id=?").use { statement ->
            statement.setString(1, requestId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    if (rows.getString(1) != operation || rows.getString(2) != hash) {
                        throw UnderstandingInputException("requestId 已用于不同内容")
                    }
                    rows.getString(3)
                }
            }
        }
    }

    fun insertRecord(db: Connection, record: UnderstandingRecord) {
        db.prepareStatement("INSERT INTO records(id, parent_id, current_revision, created_at) VALUES(?, ?, ?, ?)").use { statement ->
            statement.setString(1, record.id)
            statement.setString(2, record.parentId)
            statement.setInt(3, record.revision)
            statement.setLong(4, record.createdAt)
            statement.executeUpdate()
        }
        db.prepareStatement("INSERT INTO record_versions(record_id, revision, question_text, answer_text, data) VALUES(?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, record.id)
            statement.setInt(2, record.revision)
            statement.setString(3, record.question)
            statement.setString(4, record.answer)
            statement.setString(5, gson.toJson(record))
            statement.executeUpdate()
        }
        record.questionId?.let { id ->
            db.prepareStatement("UPDATE questions SET status='answered' WHERE id=?").use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
    }

    fun insertAnswer(db: Connection, answer: UnderstandingAnswer) {
        db.prepareStatement("INSERT INTO answers(id, question_id, answer_text, data) VALUES(?, ?, ?, ?)").use { statement ->
            statement.setString(1, answer.id)
            statement.setString(2, answer.questionId)
            statement.setString(3, answer.answer)
            statement.setString(4, gson.toJson(answer))
            statement.executeUpdate()
        }
        db.prepareStatement("UPDATE questions SET status='answered' WHERE id=?").use { statement ->
            statement.setString(1, answer.questionId)
            statement.executeUpdate()
        }
    }

    fun getAnswer(id: String): UnderstandingAnswer = connect().use { db ->
        db.prepareStatement("SELECT data FROM answers WHERE id=?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw UnderstandingInputException("旧回答不存在")
                gson.fromJson(rows.getString(1), UnderstandingAnswer::class.java)
            }
        }
    }

    fun getRecord(id: String): UnderstandingRecord = connect().use { db ->
        db.prepareStatement("SELECT v.data FROM records r JOIN record_versions v ON v.record_id=r.id AND v.revision=r.current_revision WHERE r.id=?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw UnderstandingInputException("理解记录不存在")
                gson.fromJson(rows.getString(1), UnderstandingRecord::class.java)
            }
        }
    }

    fun recentRecords(query: String = "", limit: Int = 100): List<UnderstandingRecord> = connect().use { db ->
        val result = mutableListOf<UnderstandingRecord>()
        db.prepareStatement(
            "SELECT v.data FROM records r JOIN record_versions v ON v.record_id=r.id AND v.revision=r.current_revision " +
                "WHERE (?='' OR v.question_text LIKE ? OR v.answer_text LIKE ? OR EXISTS " +
                "(SELECT 1 FROM questions q WHERE q.owner_record_id=r.id AND (q.text LIKE ? OR EXISTS " +
                "(SELECT 1 FROM answers a WHERE a.question_id=q.id AND a.answer_text LIKE ?)))) " +
                "ORDER BY r.created_at DESC LIMIT ?",
        ).use { statement ->
            val pattern = "%${query.trim()}%"
            statement.setString(1, query.trim())
            statement.setString(2, pattern)
            statement.setString(3, pattern)
            statement.setString(4, pattern)
            statement.setString(5, pattern)
            statement.setInt(6, limit.coerceIn(1, 500))
            statement.executeQuery().use { rows ->
                while (rows.next()) result += gson.fromJson(rows.getString(1), UnderstandingRecord::class.java)
            }
        }
        result
    }

    /** History groups descendants and their questions under the oldest visible parent. */
    fun recentRootRecords(query: String = "", limit: Int = 100): List<UnderstandingRecord> = connect().use { db ->
        val result = mutableListOf<UnderstandingRecord>()
        db.prepareStatement(
            "WITH RECURSIVE descendants(root_id, id) AS (" +
                "SELECT id, id FROM records WHERE parent_id IS NULL UNION ALL " +
                "SELECT d.root_id, r.id FROM records r JOIN descendants d ON r.parent_id=d.id) " +
                "SELECT v.data FROM records root JOIN record_versions v " +
                "ON v.record_id=root.id AND v.revision=root.current_revision " +
                "WHERE root.parent_id IS NULL AND (?='' OR EXISTS (" +
                "SELECT 1 FROM descendants d JOIN records r ON r.id=d.id " +
                "JOIN record_versions child ON child.record_id=r.id AND child.revision=r.current_revision " +
                "WHERE d.root_id=root.id AND (child.question_text LIKE ? OR child.answer_text LIKE ? " +
                "OR EXISTS (SELECT 1 FROM questions q WHERE q.owner_record_id=r.id AND " +
                "(q.text LIKE ? OR EXISTS (SELECT 1 FROM answers a WHERE a.question_id=q.id AND a.answer_text LIKE ?)))))) " +
                "ORDER BY root.created_at DESC LIMIT ?",
        ).use { statement ->
            val normalized = query.trim()
            val pattern = "%$normalized%"
            statement.setString(1, normalized)
            (2..5).forEach { statement.setString(it, pattern) }
            statement.setInt(6, limit.coerceIn(1, 500))
            statement.executeQuery().use { rows ->
                while (rows.next()) result += gson.fromJson(rows.getString(1), UnderstandingRecord::class.java)
            }
        }
        result
    }

    fun recordForQuestion(questionId: String): UnderstandingRecord? = connect().use { db ->
        db.prepareStatement(
            "SELECT v.data FROM records r JOIN record_versions v " +
                "ON v.record_id=r.id AND v.revision=r.current_revision " +
                "WHERE json_extract(v.data, '$.questionId')=? ORDER BY r.created_at DESC LIMIT 1",
        ).use { statement ->
            statement.setString(1, questionId)
            statement.executeQuery().use { rows ->
                if (rows.next()) gson.fromJson(rows.getString(1), UnderstandingRecord::class.java) else null
            }
        }
    }

    fun answersForRecord(recordId: String): List<Pair<UnderstandingQuestion, UnderstandingAnswer>> = connect().use { db ->
        val result = mutableListOf<Pair<UnderstandingQuestion, UnderstandingAnswer>>()
        db.prepareStatement("SELECT q.data, a.data FROM questions q JOIN answers a ON a.question_id=q.id WHERE q.owner_record_id=? " +
            "AND NOT EXISTS (SELECT 1 FROM answers newer WHERE json_extract(newer.data, '$.supersedesId')=a.id) ORDER BY a.rowid").use { statement ->
            statement.setString(1, recordId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result += gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java) to
                        gson.fromJson(rows.getString(2), UnderstandingAnswer::class.java)
                }
            }
        }
        result
    }

    fun answersForQuestion(questionId: String): List<UnderstandingAnswer> = connect().use { db ->
        val result = mutableListOf<UnderstandingAnswer>()
        db.prepareStatement("SELECT a.data FROM answers a WHERE a.question_id=? " +
            "AND NOT EXISTS (SELECT 1 FROM answers newer WHERE json_extract(newer.data, '$.supersedesId')=a.id) ORDER BY a.rowid").use { statement ->
            statement.setString(1, questionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) result += gson.fromJson(rows.getString(1), UnderstandingAnswer::class.java)
            }
        }
        result
    }

    fun questionsForRecord(recordId: String): List<UnderstandingQuestion> = connect().use { db ->
        val result = mutableListOf<UnderstandingQuestion>()
        db.prepareStatement(
            "SELECT q.data, q.owner_record_id, q.status FROM questions q WHERE q.owner_record_id=? OR EXISTS (" +
                "SELECT 1 FROM records child JOIN record_versions v " +
                "ON v.record_id=child.id AND v.revision=child.current_revision " +
                "WHERE child.parent_id=? AND json_extract(v.data, '$.questionId')=q.id) ORDER BY q.rowid",
        ).use { statement ->
            statement.setString(1, recordId)
            statement.setString(2, recordId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result += gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java).copy(
                        ownerRecordId = rows.getString(2),
                        status = rows.getString(3),
                    )
                }
            }
        }
        result
    }

    fun questionsForContext(contextToken: String): List<UnderstandingQuestion> = connect().use { db ->
        val result = mutableListOf<UnderstandingQuestion>()
        db.prepareStatement("SELECT data, owner_record_id, status FROM questions WHERE context_token=? ORDER BY rowid").use { statement ->
            statement.setString(1, contextToken)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result += gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java).copy(
                        ownerRecordId = rows.getString(2),
                        status = rows.getString(3),
                    )
                }
            }
        }
        result
    }

    fun recentQuestions(query: String = "", limit: Int = 100): List<UnderstandingQuestion> = connect().use { db ->
        val result = mutableListOf<UnderstandingQuestion>()
        db.prepareStatement(
            "SELECT q.data, q.owner_record_id, q.status FROM questions q WHERE (?='' OR q.text LIKE ? OR EXISTS " +
                "(SELECT 1 FROM answers a WHERE a.question_id=q.id AND a.answer_text LIKE ?)) ORDER BY q.rowid DESC LIMIT ?",
        ).use { statement ->
            val pattern = "%${query.trim()}%"
            statement.setString(1, query.trim())
            statement.setString(2, pattern)
            statement.setString(3, pattern)
            statement.setInt(4, limit.coerceIn(1, 500))
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result += gson.fromJson(rows.getString(1), UnderstandingQuestion::class.java).copy(
                        ownerRecordId = rows.getString(2),
                        status = rows.getString(3),
                    )
                }
            }
        }
        result
    }

    fun saveBrowseState(state: BrowseState) {
        getRecord(state.recordId)
        write { db ->
            db.prepareStatement("INSERT INTO browse_state(record_id, data) VALUES(?, ?) ON CONFLICT(record_id) DO UPDATE SET data=excluded.data").use { statement ->
                statement.setString(1, state.recordId)
                statement.setString(2, gson.toJson(state))
                statement.executeUpdate()
            }
        }
    }

    fun getBrowseState(recordId: String): BrowseState? = connect().use { db ->
        db.prepareStatement("SELECT data FROM browse_state WHERE record_id=?").use { statement ->
            statement.setString(1, recordId)
            statement.executeQuery().use { rows ->
                if (rows.next()) gson.fromJson(rows.getString(1), BrowseState::class.java) else null
            }
        }
    }

    override fun close() {
        writerLock?.release()
        lockChannel.close()
    }
}

internal fun newId(): String = UUID.randomUUID().toString()

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
