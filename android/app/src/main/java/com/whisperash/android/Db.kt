package com.whisperash.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class HistoryItem(
    val id: String,
    val text: String,
    val rawText: String?,
    val timestamp: String,
    val createdMs: Long,
    val provider: String?,
    val model: String?,
    val audioMs: Long,
    val app: String?,
)

/** A note in the Notes tab: title + bullet body, the original words kept in [raw]. */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val raw: String,
    val prevBody: String?,
    val pinned: Boolean,
    val createdMs: Long,
    val updatedMs: Long,
)

/** Local store. Every syncable row has updated_at (ms) + dirty + deleted, last write wins. */
class Db(context: Context) : SQLiteOpenHelper(context, "whisperash.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE history (
                id TEXT PRIMARY KEY, text TEXT NOT NULL, raw_text TEXT, timestamp TEXT NOT NULL,
                created_ms INTEGER NOT NULL, provider TEXT, model TEXT, audio_duration_ms INTEGER NOT NULL DEFAULT 0,
                app TEXT, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX history_created ON history(created_ms)")
        db.execSQL(
            """CREATE TABLE dictionary (
                id TEXT PRIMARY KEY, word TEXT NOT NULL, updated_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE usage_events (ts INTEGER NOT NULL, kind TEXT NOT NULL,
                billed_seconds REAL NOT NULL DEFAULT 0, tokens INTEGER NOT NULL DEFAULT 0, model TEXT)"""
        )
        db.execSQL("CREATE INDEX usage_ts ON usage_events(ts)")
        db.execSQL("CREATE TABLE timings (ts INTEGER NOT NULL, data TEXT NOT NULL)")
        createNotes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createNotes(db)
    }

    private fun createNotes(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS notes (
                id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, raw TEXT NOT NULL DEFAULT '',
                prev_body TEXT, pinned INTEGER NOT NULL DEFAULT 0, created_ms INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    // ---------- notes ----------
    fun notes(query: String = ""): List<Note> {
        val args = mutableListOf<String>()
        var where = "deleted = 0"
        if (query.isNotBlank()) {
            where += " AND (title LIKE ? OR body LIKE ? OR raw LIKE ?)"
            val q = "%${query.trim()}%"
            args += listOf(q, q, q)
        }
        val out = ArrayList<Note>()
        readableDatabase.query("notes", null, where, args.toTypedArray(), null, null, "pinned DESC, updated_at DESC").use { c ->
            while (c.moveToNext()) out += readNote(c)
        }
        return out
    }

    fun note(id: String): Note? =
        readableDatabase.query("notes", null, "id = ? AND deleted = 0", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) readNote(c) else null
        }

    private fun readNote(c: android.database.Cursor) = Note(
        c.getString(c.getColumnIndexOrThrow("id")),
        c.getString(c.getColumnIndexOrThrow("title")),
        c.getString(c.getColumnIndexOrThrow("body")),
        c.getString(c.getColumnIndexOrThrow("raw")),
        c.getString(c.getColumnIndexOrThrow("prev_body")),
        c.getInt(c.getColumnIndexOrThrow("pinned")) == 1,
        c.getLong(c.getColumnIndexOrThrow("created_ms")),
        c.getLong(c.getColumnIndexOrThrow("updated_at")),
    )

    /** Inserts or updates a note (marks it for upload). */
    fun saveNote(n: Note): Note {
        val now = System.currentTimeMillis()
        val saved = n.copy(updatedMs = now)
        writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("id", saved.id); put("title", saved.title); put("body", saved.body); put("raw", saved.raw)
            put("prev_body", saved.prevBody); put("pinned", if (saved.pinned) 1 else 0); put("created_ms", saved.createdMs)
            put("updated_at", now); put("deleted", 0); put("dirty", 1)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return saved
    }

    fun deleteNote(id: String) {
        writableDatabase.execSQL(
            "UPDATE notes SET deleted = 1, dirty = 1, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(System.currentTimeMillis(), id)
        )
    }

    fun dirtyNotes(limit: Int): List<Outgoing> {
        val out = ArrayList<Outgoing>()
        readableDatabase.rawQuery(
            "SELECT id, title, body, raw, pinned, created_ms, updated_at, deleted FROM notes WHERE dirty = 1 LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val deleted = c.getInt(7) == 1
                val data = if (deleted) JSONObject() else JSONObject().apply {
                    put("title", c.getString(1)); put("body", c.getString(2)); put("raw", c.getString(3))
                    put("pinned", c.getInt(4) == 1); put("created_ms", c.getLong(5))
                }
                out += Outgoing("notes", c.getString(0), c.getLong(6), deleted, data)
            }
        }
        return out
    }

    fun markNotesClean(items: List<Outgoing>) {
        for (it in items) writableDatabase.execSQL(
            "UPDATE notes SET dirty = 0 WHERE id = ? AND updated_at = ?", arrayOf<Any?>(it.id, it.updatedAt)
        )
    }

    fun applyRemoteNote(id: String, updatedAt: Long, deleted: Boolean, data: JSONObject): Boolean {
        val db = writableDatabase
        db.rawQuery("SELECT updated_at, dirty FROM notes WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                val localTs = c.getLong(0)
                val dirty = c.getInt(1) == 1
                if (dirty && localTs > updatedAt) return false
                if (!dirty && localTs >= updatedAt) return false
            } else if (deleted) return false
        }
        if (deleted) {
            db.execSQL("UPDATE notes SET deleted = 1, dirty = 0, updated_at = ? WHERE id = ?", arrayOf<Any?>(updatedAt, id))
        } else {
            db.insertWithOnConflict("notes", null, ContentValues().apply {
                put("id", id); put("title", data.optString("title", "")); put("body", data.optString("body", ""))
                put("raw", data.optString("raw", "")); put("pinned", if (data.optBoolean("pinned", false)) 1 else 0)
                put("created_ms", data.optLong("created_ms", updatedAt)); put("updated_at", updatedAt)
                put("deleted", 0); put("dirty", 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return true
    }

    fun dirtyNoteCount(): Int = count("SELECT COUNT(*) FROM notes WHERE dirty = 1")

    // ---------- history ----------
    fun addHistory(
        text: String, rawText: String?, provider: String?, model: String?, audioMs: Long, app: String?,
    ): HistoryItem {
        val now = System.currentTimeMillis()
        val item = HistoryItem(UUID.randomUUID().toString(), text, rawText, sqlTimestamp(now), now, provider, model, audioMs, app)
        writableDatabase.insert("history", null, ContentValues().apply {
            put("id", item.id); put("text", text); put("raw_text", rawText); put("timestamp", item.timestamp)
            put("created_ms", now); put("provider", provider); put("model", model)
            put("audio_duration_ms", audioMs); put("app", app); put("updated_at", now); put("dirty", 1)
        })
        return item
    }

    fun history(query: String = "", limit: Int = 500): List<HistoryItem> {
        val args = mutableListOf<String>()
        var where = "deleted = 0"
        if (query.isNotBlank()) {
            where += " AND text LIKE ?"
            args += "%${query.trim()}%"
        }
        val out = ArrayList<HistoryItem>()
        readableDatabase.query(
            "history", null, where, args.toTypedArray(), null, null, "created_ms DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += HistoryItem(
                    c.getString(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("text")),
                    c.getString(c.getColumnIndexOrThrow("raw_text")),
                    c.getString(c.getColumnIndexOrThrow("timestamp")),
                    c.getLong(c.getColumnIndexOrThrow("created_ms")),
                    c.getString(c.getColumnIndexOrThrow("provider")),
                    c.getString(c.getColumnIndexOrThrow("model")),
                    c.getLong(c.getColumnIndexOrThrow("audio_duration_ms")),
                    c.getString(c.getColumnIndexOrThrow("app")),
                )
            }
        }
        return out
    }

    fun historyCount(): Int = count("SELECT COUNT(*) FROM history WHERE deleted = 0")

    fun deleteHistory(id: String) {
        writableDatabase.execSQL(
            "UPDATE history SET deleted = 1, dirty = 1, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(System.currentTimeMillis(), id)
        )
    }

    fun wordsSince(ms: Long): Pair<Int, Int> {
        var words = 0
        var n = 0
        readableDatabase.rawQuery("SELECT text FROM history WHERE deleted = 0 AND created_ms >= ?", arrayOf(ms.toString()))
            .use { c ->
                while (c.moveToNext()) {
                    n++
                    words += c.getString(0).trim().split(Regex("\\s+")).count { it.isNotEmpty() }
                }
            }
        return words to n
    }

    // ---------- dictionary ----------
    fun dictionary(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT word FROM dictionary WHERE deleted = 0 ORDER BY word COLLATE NOCASE", null)
            .use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.isEmpty() || w.length > 90) return
        writableDatabase.execSQL(
            """INSERT INTO dictionary (id, word, updated_at, deleted, dirty) VALUES (?, ?, ?, 0, 1)
               ON CONFLICT(id) DO UPDATE SET word = excluded.word, updated_at = excluded.updated_at, deleted = 0, dirty = 1""",
            arrayOf<Any?>("w:" + w.lowercase(), w, System.currentTimeMillis())
        )
    }

    fun removeWord(word: String) {
        writableDatabase.execSQL(
            "UPDATE dictionary SET deleted = 1, dirty = 1, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(System.currentTimeMillis(), "w:" + word.trim().lowercase())
        )
    }

    // ---------- sync ----------
    data class Outgoing(val kind: String, val id: String, val updatedAt: Long, val deleted: Boolean, val data: JSONObject)

    fun dirtyItems(limit: Int): List<Outgoing> {
        val out = ArrayList<Outgoing>()
        readableDatabase.rawQuery(
            "SELECT id, text, raw_text, timestamp, provider, model, audio_duration_ms, updated_at, deleted FROM history WHERE dirty = 1 LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val deleted = c.getInt(8) == 1
                val data = if (deleted) JSONObject() else JSONObject().apply {
                    put("text", c.getString(1)); put("raw_text", c.getString(2) ?: JSONObject.NULL)
                    put("timestamp", c.getString(3)); put("provider", c.getString(4) ?: JSONObject.NULL)
                    put("model", c.getString(5) ?: JSONObject.NULL); put("audio_duration_ms", c.getLong(6))
                }
                out += Outgoing("history", c.getString(0), c.getLong(7), deleted, data)
            }
        }
        if (out.size < limit) {
            readableDatabase.rawQuery(
                "SELECT id, word, updated_at, deleted FROM dictionary WHERE dirty = 1 LIMIT ?",
                arrayOf((limit - out.size).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val deleted = c.getInt(3) == 1
                    out += Outgoing(
                        "dictionary", c.getString(0), c.getLong(2), deleted,
                        if (deleted) JSONObject() else JSONObject().put("word", c.getString(1))
                    )
                }
            }
        }
        return out
    }

    fun dirtyCount(): Int =
        count("SELECT COUNT(*) FROM history WHERE dirty = 1") + count("SELECT COUNT(*) FROM dictionary WHERE dirty = 1")

    fun markClean(items: List<Outgoing>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (it in items) {
                val table = if (it.kind == "history") "history" else "dictionary"
                db.execSQL("UPDATE $table SET dirty = 0 WHERE id = ? AND updated_at = ?", arrayOf<Any?>(it.id, it.updatedAt))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Applies one pulled item. Returns true when something changed locally. */
    fun applyRemote(kind: String, id: String, updatedAt: Long, deleted: Boolean, data: JSONObject): Boolean {
        val db = writableDatabase
        val table = when (kind) {
            "history" -> "history"
            "dictionary" -> "dictionary"
            else -> return false
        }
        db.rawQuery("SELECT updated_at, dirty FROM $table WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                val localTs = c.getLong(0)
                val dirty = c.getInt(1) == 1
                if (dirty && localTs > updatedAt) return false
                if (!dirty && localTs >= updatedAt) return false
            } else if (deleted) {
                return false
            }
        }
        if (kind == "history") {
            if (deleted) {
                db.execSQL("UPDATE history SET deleted = 1, dirty = 0, updated_at = ? WHERE id = ?", arrayOf<Any?>(updatedAt, id))
            } else {
                val ts = data.optString("timestamp", "").ifEmpty { sqlTimestamp(updatedAt) }
                db.insertWithOnConflict("history", null, ContentValues().apply {
                    put("id", id); put("text", data.optString("text", ""))
                    put("raw_text", if (data.isNull("raw_text")) null else data.optString("raw_text"))
                    put("timestamp", ts); put("created_ms", parseTimestamp(ts) ?: updatedAt)
                    put("provider", if (data.isNull("provider")) null else data.optString("provider"))
                    put("model", if (data.isNull("model")) null else data.optString("model"))
                    put("audio_duration_ms", data.optLong("audio_duration_ms", 0)); put("app", null as String?)
                    put("updated_at", updatedAt); put("deleted", 0); put("dirty", 0)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        } else {
            val word = data.optString("word", "").ifEmpty { id.removePrefix("w:") }
            db.execSQL(
                """INSERT INTO dictionary (id, word, updated_at, deleted, dirty) VALUES (?, ?, ?, ?, 0)
                   ON CONFLICT(id) DO UPDATE SET word = excluded.word, updated_at = excluded.updated_at,
                     deleted = excluded.deleted, dirty = 0""",
                arrayOf<Any?>(id, word, updatedAt, if (deleted) 1 else 0)
            )
        }
        return true
    }

    /** Switching accounts: everything local becomes new to the next account. */
    fun markAllDirty() {
        writableDatabase.execSQL("UPDATE history SET dirty = 1 WHERE deleted = 0")
        writableDatabase.execSQL("UPDATE dictionary SET dirty = 1 WHERE deleted = 0")
    }

    // ---------- Groq usage ----------
    fun recordUsage(kind: String, audioSeconds: Double, tokens: Int, model: String?) {
        val billed = if (kind == "stt") maxOf(GroqLimits.MIN_BILLED_SECONDS.toDouble(), Math.ceil(audioSeconds)) else 0.0
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "INSERT INTO usage_events (ts, kind, billed_seconds, tokens, model) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(now, kind, billed, tokens, model)
        )
        writableDatabase.execSQL("DELETE FROM usage_events WHERE ts < ?", arrayOf(now - 2 * 86_400_000L))
    }

    data class Window(val requests: Int, val seconds: Double, val tokens: Long)

    fun usage(kind: String, sinceMs: Long): Window {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(billed_seconds),0), COALESCE(SUM(tokens),0) FROM usage_events WHERE kind = ? AND ts >= ?",
            arrayOf(kind, sinceMs.toString())
        ).use { c ->
            c.moveToFirst()
            return Window(c.getInt(0), c.getDouble(1), c.getLong(2))
        }
    }

    fun llmUsage(model: String, sinceMs: Long): Window {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), 0, COALESCE(SUM(tokens),0) FROM usage_events WHERE kind = 'llm' AND model = ? AND ts >= ?",
            arrayOf(model, sinceMs.toString())
        ).use { c -> c.moveToFirst(); return Window(c.getInt(0), 0.0, c.getLong(2)) }
    }

    // ---------- timings ----------
    fun recordTiming(data: JSONObject) {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL("INSERT INTO timings (ts, data) VALUES (?, ?)", arrayOf<Any?>(now, data.toString()))
        writableDatabase.execSQL("DELETE FROM timings WHERE ts < ?", arrayOf(now - 7 * 86_400_000L))
    }

    fun recentTimings(n: Int): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        readableDatabase.rawQuery("SELECT data FROM timings ORDER BY ts DESC LIMIT ?", arrayOf(n.toString())).use { c ->
            while (c.moveToNext()) out += JSONObject(c.getString(0))
        }
        return out
    }

    private fun count(sql: String): Int = readableDatabase.rawQuery(sql, null).use { c -> c.moveToFirst(); c.getInt(0) }

    companion object {
        /** Same text format as the desktop's SQLite CURRENT_TIMESTAMP (UTC). */
        fun sqlTimestamp(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        fun parseTimestamp(s: String): Long? {
            val t = s.trim()
            val patterns = listOf(
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss",
            )
            for (p in patterns) {
                try {
                    val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                    return f.parse(t)?.time
                } catch (_: Exception) {
                }
            }
            return null
        }

        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun startOfMonth(): Long = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
