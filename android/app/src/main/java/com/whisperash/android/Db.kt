package com.whisperash.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
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
    /** Kept recording (filesDir/recordings) for the last [RecordingStore.KEEP] dictations; null when pruned. */
    val audioPath: String? = null,
    /** The text before "Re-transcribe (better model)" replaced it, for Undo / "Restore previous text"; this phone only. */
    val prevText: String? = null,
)

/**
 * A note: title + body written the way it was said ("- " lists, "- [ ]" checklists), the original words kept in
 * [raw]. [pinned] is the Favourite star (same synced field as before). [binnedAt] = in the recycle bin since then.
 * [audio] = the voice parts recorded into the note: on this phone only, never synced.
 */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val raw: String,
    val prevBody: String?,
    val pinned: Boolean,
    val createdMs: Long,
    val updatedMs: Long,
    val folderId: String? = null,
    val locked: Boolean = false,
    val binnedAt: Long? = null,
    val audio: List<AudioClip> = emptyList(),
    val editedMs: Long = updatedMs,
) {
    val favourite get() = pinned
}

/** A notes folder (Samsung Notes style: nested, with an optional colour key). */
data class Folder(val id: String, val name: String, val parentId: String?, val color: String?, val updatedMs: Long = 0)

/** One voice part of a note: the recording file (app storage) and its length. */
data class AudioClip(val path: String, val durationMs: Long, val createdMs: Long) {
    companion object {
        /** Stored as lines "createdMs|durationMs|path" (no JSON, so plain JVM tests can read it). */
        fun encode(clips: List<AudioClip>): String? =
            clips.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.createdMs}|${it.durationMs}|${it.path}" }

        fun decode(s: String?): List<AudioClip> = s.orEmpty().lines().mapNotNull { l ->
            val p = l.split('|', limit = 3)
            if (p.size == 3) AudioClip(p[2], p[1].toLongOrNull() ?: 0, p[0].toLongOrNull() ?: 0) else null
        }
    }
}

/**
 * A recorded meeting: speaker-labelled Roman Hinglish transcript + summary. [status] is "recording",
 * "transcribing", "done" or "failed"; only done / failed meetings sync (text only, never the audio).
 */
data class Meeting(
    val id: String,
    val title: String,
    val summary: String,
    val transcript: String,
    val engine: String,
    val durationMs: Long,
    val createdMs: Long,
    val updatedMs: Long,
    val audioPath: String?,
    val status: String,
    val error: String?,
    /** Before "Re-transcribe (better model)": the old title / summary / transcript / engine, for Undo. This phone only. */
    val prevTitle: String? = null,
    val prevSummary: String? = null,
    val prevTranscript: String? = null,
    val prevEngine: String? = null,
    val kind: RecordingKind = RecordingKind.MEETING,
    val diaryOwner: String = "",
)

/** Local store. Every syncable row has updated_at (ms) + dirty + deleted, last write wins. */
class Db(context: Context) : SQLiteOpenHelper(context, "whisperash.db", null, 6) {

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
        createMeetings(db)
        upgradeTo4(db)
        upgradeTo5(db)
        upgradeTo6(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createNotes(db)
        if (oldVersion < 3) createMeetings(db)
        if (oldVersion < 4) upgradeTo4(db)
        if (oldVersion < 5) upgradeTo5(db)
        if (oldVersion < 6) upgradeTo6(db)
    }

    private fun upgradeTo6(db: SQLiteDatabase) {
        addColumn(db, "meetings", "entry_kind", "TEXT NOT NULL DEFAULT 'meetings'")
        addColumn(db, "meetings", "diary_owner", "TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS recordings_kind_owner ON meetings(entry_kind, diary_owner, created_ms)")
    }

    /**
     * v4, additive only: folders, note folder / lock / recycle bin / voice parts / edited time, and the kept
     * recording of recent dictations. Existing rows keep every value; old notes land in "All notes".
     */
    private fun upgradeTo4(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS folders (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, parent_id TEXT, color TEXT,
                updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0)"""
        )
        addColumn(db, "notes", "folder_id", "TEXT")
        addColumn(db, "notes", "locked", "INTEGER NOT NULL DEFAULT 0")
        addColumn(db, "notes", "binned_at", "INTEGER")
        addColumn(db, "notes", "audio", "TEXT")
        addColumn(db, "notes", "edited_at", "INTEGER")
        addColumn(db, "history", "audio_path", "TEXT")
    }

    /** v5, additive only: what "Re-transcribe (better model)" replaced, so it can be undone (never synced). */
    private fun upgradeTo5(db: SQLiteDatabase) {
        addColumn(db, "history", "prev_text", "TEXT")
        addColumn(db, "meetings", "prev_title", "TEXT")
        addColumn(db, "meetings", "prev_summary", "TEXT")
        addColumn(db, "meetings", "prev_transcript", "TEXT")
        addColumn(db, "meetings", "prev_engine", "TEXT")
    }

    private fun addColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) if (c.getString(1) == column) return
        }
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }

    private fun createMeetings(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS meetings (
                id TEXT PRIMARY KEY, title TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '', transcript TEXT NOT NULL DEFAULT '',
                engine TEXT NOT NULL DEFAULT '', duration_ms INTEGER NOT NULL DEFAULT 0, created_ms INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0,
                audio_path TEXT, status TEXT NOT NULL DEFAULT 'done', error TEXT)"""
        )
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
    /** Every note that is not a sync tombstone (bin included); the screen filters, sorts and groups in memory. */
    fun allNotes(): List<Note> {
        val out = ArrayList<Note>()
        readableDatabase.query("notes", null, "deleted = 0", null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) out += readNote(c)
        }
        return out
    }

    /** Notes outside the recycle bin, favourites first, newest first (Home's count). */
    fun notes(query: String = ""): List<Note> =
        NoteQuery.apply(allNotes(), NoteView.All, query, NoteSort.Modified, emptyMap())

    fun note(id: String): Note? =
        readableDatabase.query("notes", null, "id = ? AND deleted = 0", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) readNote(c) else null
        }

    private fun readNote(c: android.database.Cursor): Note {
        fun str(n: String) = c.getColumnIndex(n).takeIf { it >= 0 }?.let { c.getString(it) }
        fun lng(n: String) = c.getColumnIndex(n).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
        val updated = c.getLong(c.getColumnIndexOrThrow("updated_at"))
        return Note(
            c.getString(c.getColumnIndexOrThrow("id")),
            c.getString(c.getColumnIndexOrThrow("title")),
            c.getString(c.getColumnIndexOrThrow("body")),
            c.getString(c.getColumnIndexOrThrow("raw")),
            c.getString(c.getColumnIndexOrThrow("prev_body")),
            c.getInt(c.getColumnIndexOrThrow("pinned")) == 1,
            c.getLong(c.getColumnIndexOrThrow("created_ms")),
            updated,
            folderId = str("folder_id"),
            locked = (lng("locked") ?: 0L) == 1L,
            binnedAt = lng("binned_at"),
            audio = AudioClip.decode(str("audio")),
            editedMs = lng("edited_at") ?: updated,
        )
    }

    /**
     * Inserts or updates a note (marks it for upload). [edited] = the words changed (the "Edited 2:40 pm" time);
     * moving, favouriting or locking only changes updated_at.
     */
    fun saveNote(n: Note, edited: Boolean = true): Note {
        val now = System.currentTimeMillis()
        val saved = n.copy(updatedMs = now, editedMs = if (edited) now else n.editedMs)
        writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("id", saved.id); put("title", saved.title); put("body", saved.body); put("raw", saved.raw)
            put("prev_body", saved.prevBody); put("pinned", if (saved.pinned) 1 else 0); put("created_ms", saved.createdMs)
            put("updated_at", now); put("deleted", 0); put("dirty", 1)
            put("folder_id", saved.folderId); put("locked", if (saved.locked) 1 else 0); put("binned_at", saved.binnedAt)
            put("audio", AudioClip.encode(saved.audio)); put("edited_at", saved.editedMs)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return saved
    }

    /** Delete = into the recycle bin (kept [NoteQuery.BIN_DAYS] days, can be restored). */
    fun binNote(id: String) {
        writableDatabase.execSQL(
            "UPDATE notes SET binned_at = ?, dirty = 1, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(System.currentTimeMillis(), System.currentTimeMillis(), id)
        )
    }

    fun restoreNote(id: String) {
        writableDatabase.execSQL("UPDATE notes SET binned_at = NULL, dirty = 1, updated_at = ? WHERE id = ?", arrayOf<Any?>(System.currentTimeMillis(), id))
    }

    /** Gone for good: a sync tombstone, and the note's recordings are deleted from the phone. */
    fun deleteNote(id: String) {
        note(id)?.audio?.forEach { java.io.File(it.path).delete() }
        writableDatabase.execSQL(
            "UPDATE notes SET deleted = 1, dirty = 1, updated_at = ?, body = '', raw = '', prev_body = NULL, audio = NULL WHERE id = ?",
            arrayOf<Any?>(System.currentTimeMillis(), id)
        )
    }

    /** Empties bin entries older than [NoteQuery.BIN_DAYS] days (Samsung Notes' rule). Returns how many. */
    fun purgeBin(now: Long = System.currentTimeMillis()): Int {
        val old = allNotes().filter { NoteQuery.binExpired(it, now) }
        old.forEach { deleteNote(it.id) }
        return old.size
    }

    fun dirtyNotes(limit: Int): List<Outgoing> {
        val out = ArrayList<Outgoing>()
        readableDatabase.rawQuery("SELECT * FROM notes WHERE dirty = 1 LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) {
                val deleted = c.getInt(c.getColumnIndexOrThrow("deleted")) == 1
                val n = readNote(c)
                out += Outgoing("notes", n.id, n.updatedMs, deleted, if (deleted) JSONObject() else NoteSync.toJson(n))
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
        var local: Note? = null
        db.rawQuery("SELECT * FROM notes WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                val localTs = c.getLong(c.getColumnIndexOrThrow("updated_at"))
                val dirty = c.getInt(c.getColumnIndexOrThrow("dirty")) == 1
                if (dirty && localTs > updatedAt) return false
                if (!dirty && localTs >= updatedAt) return false
                local = readNote(c)
            } else if (deleted) return false
        }
        if (deleted) {
            local?.audio?.forEach { java.io.File(it.path).delete() }
            db.execSQL("UPDATE notes SET deleted = 1, dirty = 0, updated_at = ?, audio = NULL WHERE id = ?", arrayOf<Any?>(updatedAt, id))
        } else {
            val n = NoteSync.fromJson(id, updatedAt, data, local)
            db.insertWithOnConflict("notes", null, ContentValues().apply {
                put("id", id); put("title", n.title); put("body", n.body); put("raw", n.raw); put("prev_body", local?.prevBody)
                put("pinned", if (n.pinned) 1 else 0); put("created_ms", n.createdMs); put("updated_at", updatedAt)
                put("deleted", 0); put("dirty", 0)
                put("folder_id", n.folderId); put("locked", if (n.locked) 1 else 0); put("binned_at", n.binnedAt)
                put("audio", AudioClip.encode(n.audio)); put("edited_at", n.editedMs)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return true
    }

    fun dirtyNoteCount(): Int = count("SELECT COUNT(*) FROM notes WHERE dirty = 1") + count("SELECT COUNT(*) FROM folders WHERE dirty = 1")

    // ---------- folders ----------
    fun folders(): List<Folder> {
        val out = ArrayList<Folder>()
        readableDatabase.rawQuery("SELECT id, name, parent_id, color, updated_at FROM folders WHERE deleted = 0 ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out += Folder(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))
        }
        return out
    }

    fun saveFolder(f: Folder): Folder {
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict("folders", null, ContentValues().apply {
            put("id", f.id); put("name", f.name); put("parent_id", f.parentId); put("color", f.color)
            put("updated_at", now); put("deleted", 0); put("dirty", 1)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return f.copy(updatedMs = now)
    }

    /** Deletes a folder and its subfolders; their notes move to "All notes" (no folder), never into the bin. */
    fun deleteFolder(id: String) {
        val now = System.currentTimeMillis()
        val ids = FolderTree.descendants(folders(), id) + id
        for (f in ids) {
            writableDatabase.execSQL("UPDATE folders SET deleted = 1, dirty = 1, updated_at = ? WHERE id = ?", arrayOf<Any?>(now, f))
            writableDatabase.execSQL("UPDATE notes SET folder_id = NULL, dirty = 1, updated_at = ? WHERE folder_id = ?", arrayOf<Any?>(now, f))
        }
    }

    fun dirtyFolders(limit: Int): List<Outgoing> {
        val out = ArrayList<Outgoing>()
        readableDatabase.rawQuery("SELECT id, name, parent_id, color, updated_at, deleted FROM folders WHERE dirty = 1 LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) {
                val deleted = c.getInt(5) == 1
                val data = if (deleted) JSONObject() else JSONObject().apply {
                    put("name", c.getString(1)); put("parentId", c.getString(2) ?: JSONObject.NULL); put("color", c.getString(3) ?: JSONObject.NULL)
                }
                out += Outgoing("folders", c.getString(0), c.getLong(4), deleted, data)
            }
        }
        return out
    }

    fun markFoldersClean(items: List<Outgoing>) {
        for (it in items) writableDatabase.execSQL("UPDATE folders SET dirty = 0 WHERE id = ? AND updated_at = ?", arrayOf<Any?>(it.id, it.updatedAt))
    }

    fun applyRemoteFolder(id: String, updatedAt: Long, deleted: Boolean, data: JSONObject): Boolean {
        val db = writableDatabase
        db.rawQuery("SELECT updated_at, dirty FROM folders WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                val localTs = c.getLong(0); val dirty = c.getInt(1) == 1
                if (dirty && localTs > updatedAt) return false
                if (!dirty && localTs >= updatedAt) return false
            } else if (deleted) return false
        }
        db.insertWithOnConflict("folders", null, ContentValues().apply {
            put("id", id); put("name", data.optString("name", "Folder")); put("updated_at", updatedAt)
            put("parent_id", if (data.isNull("parentId")) null else data.optString("parentId").ifEmpty { null })
            put("color", if (data.isNull("color")) null else data.optString("color").ifEmpty { null })
            put("deleted", if (deleted) 1 else 0); put("dirty", 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    // ---------- meetings ----------
    fun meetings(kind: RecordingKind = RecordingKind.MEETING, diaryOwner: String = ""): List<Meeting> {
        val out = ArrayList<Meeting>()
        readableDatabase.query("meetings", null, "deleted = 0 AND entry_kind = ? AND diary_owner = ?",
            arrayOf(kind.wireName, diaryOwner), null, null, "created_ms DESC").use { c ->
            while (c.moveToNext()) out += readMeeting(c)
        }
        return out
    }

    fun meeting(id: String): Meeting? =
        readableDatabase.query("meetings", null, "id = ? AND deleted = 0", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) readMeeting(c) else null
        }

    private fun readMeeting(c: android.database.Cursor) = Meeting(
        c.getString(c.getColumnIndexOrThrow("id")),
        c.getString(c.getColumnIndexOrThrow("title")),
        c.getString(c.getColumnIndexOrThrow("summary")),
        c.getString(c.getColumnIndexOrThrow("transcript")),
        c.getString(c.getColumnIndexOrThrow("engine")),
        c.getLong(c.getColumnIndexOrThrow("duration_ms")),
        c.getLong(c.getColumnIndexOrThrow("created_ms")),
        c.getLong(c.getColumnIndexOrThrow("updated_at")),
        c.getString(c.getColumnIndexOrThrow("audio_path")),
        c.getString(c.getColumnIndexOrThrow("status")),
        c.getString(c.getColumnIndexOrThrow("error")),
        prevTitle = c.getColumnIndex("prev_title").takeIf { it >= 0 }?.let { c.getString(it) },
        prevSummary = c.getColumnIndex("prev_summary").takeIf { it >= 0 }?.let { c.getString(it) },
        prevTranscript = c.getColumnIndex("prev_transcript").takeIf { it >= 0 }?.let { c.getString(it) },
        prevEngine = c.getColumnIndex("prev_engine").takeIf { it >= 0 }?.let { c.getString(it) },
        kind = RecordingKind.fromWire(c.getString(c.getColumnIndexOrThrow("entry_kind"))),
        diaryOwner = c.getString(c.getColumnIndexOrThrow("diary_owner")),
    )

    /** Inserts or updates a meeting. Only finished (done / failed) meetings are marked for upload. */
    fun saveMeeting(m: Meeting): Meeting = writableDatabase.transaction {
        val before = meeting(m.id)
        val now = maxOf(System.currentTimeMillis(), (before?.updatedMs ?: 0) + 1)
        // A first sign-in can claim a local diary while its recorder is still active.
        val saved = m.copy(updatedMs = now, diaryOwner = before?.diaryOwner ?: m.diaryOwner)
        insertWithOnConflict("meetings", null, ContentValues().apply {
            put("id", saved.id); put("title", saved.title); put("summary", saved.summary); put("transcript", saved.transcript)
            put("engine", saved.engine); put("duration_ms", saved.durationMs); put("created_ms", saved.createdMs)
            put("updated_at", now); put("deleted", 0); put("dirty", if (saved.status in MEETING_SYNCED) 1 else 0)
            put("audio_path", saved.audioPath); put("status", saved.status); put("error", saved.error)
            put("prev_title", saved.prevTitle); put("prev_summary", saved.prevSummary)
            put("prev_transcript", saved.prevTranscript); put("prev_engine", saved.prevEngine)
            put("diary_owner", saved.diaryOwner); put("entry_kind", saved.kind.wireName)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        saved
    }

    /** Delete = tombstone (the existing sync mechanism); the text goes right away. */
    fun deleteMeeting(id: String) {
        writableDatabase.execSQL(
            "UPDATE meetings SET deleted = 1, dirty = 1, updated_at = MAX(?, updated_at + 1), audio_path = NULL, transcript = '', summary = '' WHERE id = ?",
            arrayOf<Any?>(System.currentTimeMillis(), id)
        )
    }

    /** Undo "Re-transcribe (better model)": the previous title, summary, transcript and engine come back. */
    fun restoreMeeting(id: String): Meeting? {
        val m = meeting(id) ?: return null
        if (m.prevTranscript == null) return m
        return saveMeeting(m.copy(
            title = m.prevTitle ?: m.title, summary = m.prevSummary ?: "", transcript = m.prevTranscript,
            engine = m.prevEngine ?: m.engine, error = null,
            prevTitle = null, prevSummary = null, prevTranscript = null, prevEngine = null,
        ))
    }

    /** A cancelled recording the server never saw: no tombstone needed. */
    fun dropMeeting(id: String) {
        writableDatabase.delete("meetings", "id = ?", arrayOf(id))
    }

    fun claimLocalDiaries(owner: String) {
        if (owner.isEmpty()) return
        writableDatabase.execSQL("UPDATE meetings SET diary_owner = ? WHERE entry_kind = 'diaries' AND diary_owner = ''",
            arrayOf(owner))
    }

    fun dirtyMeetings(limit: Int, kind: RecordingKind, diaryOwner: String): List<Outgoing> {
        val out = ArrayList<Outgoing>()
        readableDatabase.rawQuery(
            """SELECT id, title, summary, transcript, engine, duration_ms, created_ms, status, updated_at, deleted FROM meetings
               WHERE entry_kind = ? AND diary_owner = ? AND dirty = 1 AND (deleted = 1 OR status IN ('done', 'failed'))
               ORDER BY updated_at, id LIMIT ?""",
            arrayOf(kind.wireName, diaryOwner, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val deleted = c.getInt(9) == 1
                val data = if (deleted) JSONObject() else JSONObject().apply {
                    put("title", c.getString(1)); put("summary", c.getString(2))
                    put("transcript", MeetingText.fitForSync(c.getString(3), c.getString(1) + c.getString(2)))
                    put("engine", c.getString(4)); put("durationMs", c.getLong(5)); put("createdMs", c.getLong(6))
                    put("status", c.getString(7))
                }
                out += Outgoing(kind.wireName, c.getString(0), c.getLong(8), deleted, data)
            }
        }
        return out
    }

    fun markMeetingsClean(items: List<Outgoing>) {
        for (it in items) writableDatabase.execSQL(
            "UPDATE meetings SET dirty = 0 WHERE id = ? AND updated_at = ?", arrayOf<Any?>(it.id, it.updatedAt)
        )
    }

    fun applyRemoteMeeting(id: String, updatedAt: Long, deleted: Boolean, data: JSONObject,
        kind: RecordingKind = RecordingKind.MEETING, diaryOwner: String = ""): Boolean {
        val db = writableDatabase
        var audio: String? = null
        db.rawQuery("SELECT updated_at, dirty, audio_path, status, deleted, entry_kind, diary_owner FROM meetings WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                if (c.getString(5) != kind.wireName || c.getString(6) != diaryOwner) return false
                val localTs = c.getLong(0)
                val dirty = c.getInt(1) == 1
                // Never overwrite a meeting this phone is still recording or transcribing.
                if (c.getInt(4) == 0 && c.getString(3) !in MEETING_SYNCED) return false
                if (dirty && localTs > updatedAt) return false
                if (!dirty && localTs >= updatedAt) return false
                audio = c.getString(2)
            } else if (deleted) return false
        }
        if (deleted) {
            db.execSQL(
                "UPDATE meetings SET deleted = 1, dirty = 0, updated_at = ?, audio_path = NULL, transcript = '', summary = '' WHERE id = ?",
                arrayOf<Any?>(updatedAt, id)
            )
            audio?.let { MeetingFiles.deleteFor(it) }
        } else {
            val status = data.optString("status", "done").takeIf { it in MEETING_SYNCED } ?: "done"
            db.insertWithOnConflict("meetings", null, ContentValues().apply {
                put("id", id); put("title", data.optString("title", kind.defaultTitle(data.optLong("createdMs", updatedAt))))
                put("entry_kind", kind.wireName); put("diary_owner", diaryOwner)
                put("summary", data.optString("summary", ""))
                put("transcript", data.optString("transcript", "")); put("engine", data.optString("engine", ""))
                put("duration_ms", data.optLong("durationMs", 0)); put("created_ms", data.optLong("createdMs", updatedAt))
                put("updated_at", updatedAt); put("deleted", 0); put("dirty", 0)
                put("audio_path", audio); put("status", status); put("error", null as String?)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        return true
    }

    /** At process start: a meeting still "recording" lost its audio; one "transcribing" can be retried. */
    fun failUnfinishedMeetings() {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "UPDATE meetings SET status = 'failed', dirty = 1, updated_at = ?, error = ? WHERE status = 'recording' AND deleted = 0",
            arrayOf<Any?>(now, "The recording was interrupted (the app was closed) and could not be saved.")
        )
        writableDatabase.execSQL(
            "UPDATE meetings SET status = 'failed', dirty = 1, updated_at = ?, error = ? WHERE status = 'transcribing' AND deleted = 0",
            arrayOf<Any?>(now, "Transcription was interrupted. Tap Retry.")
        )
    }

    fun dirtyMeetingCount(diaryOwner: String): Int = readableDatabase.rawQuery(
        """SELECT COUNT(*) FROM meetings WHERE (entry_kind = 'meetings' OR diary_owner = ?)
            AND dirty = 1 AND (deleted = 1 OR status IN ('done', 'failed'))""", arrayOf(diaryOwner)
    ).use { it.moveToFirst(); it.getInt(0) }

    // ---------- history ----------
    fun addHistory(
        text: String, rawText: String?, provider: String?, model: String?, audioMs: Long, app: String?, audioPath: String? = null,
    ): HistoryItem {
        val now = System.currentTimeMillis()
        val item = HistoryItem(UUID.randomUUID().toString(), text, rawText, sqlTimestamp(now), now, provider, model, audioMs, app, audioPath)
        writableDatabase.insert("history", null, ContentValues().apply {
            put("id", item.id); put("text", text); put("raw_text", rawText); put("timestamp", item.timestamp)
            put("created_ms", now); put("provider", provider); put("model", model)
            put("audio_duration_ms", audioMs); put("app", app); put("updated_at", now); put("dirty", 1); put("audio_path", audioPath)
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
                    c.getColumnIndex("audio_path").takeIf { it >= 0 }?.let { i -> c.getString(i) },
                    c.getColumnIndex("prev_text").takeIf { it >= 0 }?.let { i -> c.getString(i) },
                )
            }
        }
        return out
    }

    /** Re-transcribe replaced the text: the old one is kept (prev_text) for Undo / "Restore previous text". Synced. */
    fun replaceHistoryText(id: String, text: String) {
        val cur = history(limit = 100_000).firstOrNull { it.id == id } ?: return
        val (newText, prev) = TextUndo.replace(cur.text, cur.prevText, text)
        writableDatabase.execSQL(
            "UPDATE history SET text = ?, prev_text = ?, dirty = 1, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(newText, prev, System.currentTimeMillis(), id)
        )
    }

    /** Undo: the previous text comes back and there is nothing left to restore. */
    fun restoreHistoryText(id: String) {
        val cur = history(limit = 100_000).firstOrNull { it.id == id } ?: return
        val (text, prev) = TextUndo.restore(cur.text, cur.prevText) ?: return
        writableDatabase.execSQL(
            "UPDATE history SET text = ?, prev_text = ?, dirty = 1, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(text, prev, System.currentTimeMillis(), id)
        )
    }

    /** History rows that still have a kept recording: (id, createdMs, path). */
    fun historyRecordings(): List<Triple<String, Long, String>> {
        val out = ArrayList<Triple<String, Long, String>>()
        readableDatabase.rawQuery("SELECT id, created_ms, audio_path FROM history WHERE audio_path IS NOT NULL", null).use { c ->
            while (c.moveToNext()) out += Triple(c.getString(0), c.getLong(1), c.getString(2))
        }
        return out
    }

    fun clearHistoryAudio(id: String) {
        writableDatabase.execSQL("UPDATE history SET audio_path = NULL WHERE id = ?", arrayOf<Any?>(id))
    }

    fun historyCount(): Int = count("SELECT COUNT(*) FROM history WHERE deleted = 0")

    fun deleteHistory(id: String) {
        historyRecordings().firstOrNull { it.first == id }?.let { java.io.File(it.third).delete() }
        writableDatabase.execSQL(
            "UPDATE history SET deleted = 1, dirty = 1, updated_at = ?, audio_path = NULL WHERE id = ?",
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
                // The recording, its app and the undo text live only on this phone: keep them.
                var keepAudio: String? = null; var keepPrev: String? = null; var keepApp: String? = null
                db.rawQuery("SELECT audio_path, prev_text, app FROM history WHERE id = ?", arrayOf(id)).use { c ->
                    if (c.moveToFirst()) { keepAudio = c.getString(0); keepPrev = c.getString(1); keepApp = c.getString(2) }
                }
                db.insertWithOnConflict("history", null, ContentValues().apply {
                    put("id", id); put("text", data.optString("text", ""))
                    put("raw_text", if (data.isNull("raw_text")) null else data.optString("raw_text"))
                    put("timestamp", ts); put("created_ms", parseTimestamp(ts) ?: updatedAt)
                    put("provider", if (data.isNull("provider")) null else data.optString("provider"))
                    put("model", if (data.isNull("model")) null else data.optString("model"))
                    put("audio_duration_ms", data.optLong("audio_duration_ms", 0)); put("app", keepApp)
                    put("audio_path", keepAudio); put("prev_text", keepPrev)
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
        val MEETING_SYNCED = setOf("done", "failed")

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
