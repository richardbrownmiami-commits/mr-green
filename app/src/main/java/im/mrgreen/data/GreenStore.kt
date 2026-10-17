package im.mrgreen.data

import android.content.ContentValues
import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteOpenHelper

/** App-private, bundled SQLite. UI callers must use an IO dispatcher. */
data class ChatMessage(val id: Long, val role: String, val content: String, val createdAt: Long)
data class LogEntry(val timestamp: Long, val level: String, val component: String, val code: String?, val message: String)

class GreenStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "mr-green.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE identity (id INTEGER PRIMARY KEY CHECK(id=1), name TEXT NOT NULL, persona TEXT NOT NULL DEFAULT '', owner_profile TEXT NOT NULL DEFAULT '', tone TEXT NOT NULL DEFAULT 'helpful', rules TEXT NOT NULL DEFAULT '')")
        db.execSQL("INSERT INTO identity(id,name) VALUES(1,'Mr Green')")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL CHECK(role IN ('user','assistant','system')), content TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE facts (id INTEGER PRIMARY KEY, key TEXT NOT NULL, value TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '', importance REAL NOT NULL DEFAULT 0.5, source TEXT NOT NULL DEFAULT '', last_used INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY, title TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0, due_at INTEGER)")
        db.execSQL("CREATE TABLE episodes (id INTEGER PRIMARY KEY, plan_json TEXT NOT NULL, result_json TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE skills (id TEXT PRIMARY KEY, triggers TEXT NOT NULL, slots TEXT NOT NULL, steps_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'proposed', successes INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 1, origin TEXT NOT NULL)")
        db.execSQL("CREATE TABLE library_templates (id TEXT PRIMARY KEY, intent TEXT NOT NULL, phrase TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 0, negative_phrases TEXT NOT NULL DEFAULT '[]')")
        db.execSQL("CREATE TABLE library_schemas (id TEXT PRIMARY KEY, schema_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE library_answers (id TEXT PRIMARY KEY, template TEXT NOT NULL)")
        db.execSQL("CREATE TABLE packs (id TEXT PRIMARY KEY, version TEXT NOT NULL, source TEXT NOT NULL, hash TEXT NOT NULL, signature TEXT NOT NULL, status TEXT NOT NULL, size_bytes INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE match_rules (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, pattern TEXT NOT NULL, replacement TEXT NOT NULL, approved INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE golden_tests (id TEXT PRIMARY KEY, request TEXT NOT NULL, expected_plan TEXT NOT NULL)")
        db.execSQL("CREATE TABLE web_recipes (id INTEGER PRIMARY KEY, source TEXT NOT NULL, url_pattern TEXT NOT NULL, recipe_json TEXT NOT NULL, success_count INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE query_recipes (id INTEGER PRIMARY KEY, name TEXT NOT NULL, select_sql TEXT NOT NULL, approved INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE incidents (id INTEGER PRIMARY KEY, fingerprint TEXT UNIQUE NOT NULL, component TEXT NOT NULL, error_code TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 1, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, card_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE doctor_changes (id INTEGER PRIMARY KEY, before_json TEXT NOT NULL, after_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'proposed', created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE self_manual (id TEXT PRIMARY KEY, component TEXT NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE TABLE notif_log (id INTEGER PRIMARY KEY, package TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE cloud_log (id INTEGER PRIMARY KEY, provider TEXT NOT NULL, data_class TEXT NOT NULL, category TEXT NOT NULL, size_bytes INTEGER NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE rag_docs (id INTEGER PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE rag_chunks (id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL REFERENCES rag_docs(id) ON DELETE CASCADE, section TEXT NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, level TEXT NOT NULL, component TEXT NOT NULL, plan_id TEXT, step_id TEXT, app_state TEXT NOT NULL, code TEXT, message TEXT NOT NULL, stack_trace TEXT)")
        db.execSQL("CREATE INDEX logs_time ON logs(timestamp)")
        db.execSQL("CREATE TRIGGER logs_ring AFTER INSERT ON logs BEGIN DELETE FROM logs WHERE id <= NEW.id - 20000; END")
        createFts(db, "messages", "content")
        createFts(db, "facts", "key, value, tags")
        createFts(db, "rag_chunks", "text")
        createFts(db, "library_templates", "phrase")
        createFts(db, "self_manual", "text")
    }

    private fun createFts(db: SQLiteDatabase, table: String, columns: String) {
        // Identifiers are compile-time constants, not plugin/model input.
        val names = columns.split(',').map { it.trim() }
        db.execSQL("CREATE VIRTUAL TABLE fts_$table USING fts5($columns, content='$table', content_rowid='rowid')")
        val newValues = names.joinToString(",") { "new.$it" }
        val oldValues = names.joinToString(",") { "old.$it" }
        db.execSQL("CREATE TRIGGER ${table}_ai AFTER INSERT ON $table BEGIN INSERT INTO fts_$table(rowid,$columns) VALUES(new.rowid,$newValues); END")
        db.execSQL("CREATE TRIGGER ${table}_ad AFTER DELETE ON $table BEGIN INSERT INTO fts_$table(fts_$table,rowid,$columns) VALUES('delete',old.rowid,$oldValues); END")
        db.execSQL("CREATE TRIGGER ${table}_au AFTER UPDATE ON $table BEGIN INSERT INTO fts_$table(fts_$table,rowid,$columns) VALUES('delete',old.rowid,$oldValues); INSERT INTO fts_$table(rowid,$columns) VALUES(new.rowid,$newValues); END")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never destroy a user's memory to hide a migration gap.
        error("No migration from $oldVersion to $newVersion")
    }

    fun identityName(): String = readableDatabase.rawQuery("SELECT name FROM identity WHERE id=1", null).use { c -> if (c.moveToFirst()) c.getString(0) else "Mr Green" }
    fun setIdentityName(name: String) {
        require(name.isNotBlank() && name.length <= 80)
        writableDatabase.update("identity", ContentValues().apply { put("name", name.trim()) }, "id=1", null)
    }
    fun addMessage(role: String, content: String): Long {
        require(role in setOf("user", "assistant", "system"))
        return writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("role", role); put("content", content); put("created_at", System.currentTimeMillis())
        })
    }
    fun messages(): List<ChatMessage> = readableDatabase.rawQuery("SELECT id,role,content,created_at FROM (SELECT id,role,content,created_at FROM messages ORDER BY id DESC LIMIT 200) ORDER BY id", null).use { c ->
        buildList { while(c.moveToNext()) add(ChatMessage(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
    }
    fun log(level: String, component: String, code: String?, message: String, appState: String, planId: String? = null, stepId: String? = null, stack: String? = null) {
        writableDatabase.insertOrThrow("logs", null, ContentValues().apply {
            put("timestamp", System.currentTimeMillis()); put("level", level); put("component", component)
            put("code", code); put("message", message.take(4000)); put("app_state", appState)
            put("plan_id", planId); put("step_id", stepId); put("stack_trace", stack?.take(16000))
        })
    }
    fun logs(query: String = ""): List<LogEntry> = readableDatabase.rawQuery(
        "SELECT timestamp,level,component,code,message FROM logs WHERE message LIKE ? OR component LIKE ? OR level LIKE ? OR code LIKE ? ORDER BY id DESC LIMIT 500",
        Array(4) { "%${query.take(200)}%" }
    ).use { c -> buildList { while (c.moveToNext()) add(LogEntry(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4))) } }
    fun fts5Available(): Boolean = try {
        readableDatabase.rawQuery("SELECT count(*) FROM fts_messages", null).use { it.moveToFirst() }
    } catch (_: Exception) { false }
}
