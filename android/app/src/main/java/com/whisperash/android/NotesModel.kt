package com.whisperash.android

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Which notes the Notes screen shows (Samsung Notes' side menu). */
sealed class NoteView {
    object All : NoteView()
    object Favourites : NoteView()
    data class InFolder(val id: String) : NoteView()
    object Bin : NoteView()
}

enum class NoteSort(val label: String) { Modified("Date modified"), Created("Date created"), Title("Title") }

enum class NoteLayout(val label: String) { Grid("Grid"), List("List"), Simple("Simple list") }

/** Filtering, sorting, date groups and the recycle-bin rule, kept free of Android so they are unit-tested. */
object NoteQuery {
    /** Samsung Notes keeps deleted notes 15 days. */
    const val BIN_DAYS = 15
    private const val DAY = 86_400_000L

    fun binExpired(n: Note, now: Long) = n.binnedAt != null && now - n.binnedAt >= BIN_DAYS * DAY

    fun daysLeftInBin(n: Note, now: Long): Int =
        if (n.binnedAt == null) BIN_DAYS else maxOf(0, BIN_DAYS - ((now - n.binnedAt) / DAY).toInt())

    /** [folderNames] (id → name) lets a search for a folder's name find its notes. Locked notes match on title only. */
    fun apply(notes: List<Note>, view: NoteView, query: String, sort: NoteSort, folderNames: Map<String, String>): List<Note> {
        val q = query.trim().lowercase()
        val picked = notes.filter { n ->
            when (view) {
                NoteView.All -> n.binnedAt == null
                NoteView.Favourites -> n.binnedAt == null && n.pinned
                is NoteView.InFolder -> n.binnedAt == null && n.folderId == view.id
                NoteView.Bin -> n.binnedAt != null
            }
        }.filter { n ->
            q.isEmpty() || n.title.lowercase().contains(q) ||
                (!n.locked && n.body.lowercase().contains(q)) ||
                (n.folderId != null && folderNames[n.folderId]?.lowercase()?.contains(q) == true)
        }
        if (view == NoteView.Bin) return picked.sortedByDescending { it.binnedAt }
        return when (sort) {
            NoteSort.Modified -> picked.sortedByDescending { it.editedMs }
            NoteSort.Created -> picked.sortedByDescending { it.createdMs }
            NoteSort.Title -> picked.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.trim() })
        }
    }

    /** The date a note is grouped and shown by, for the sort in use. */
    fun dateOf(n: Note, sort: NoteSort) = if (sort == NoteSort.Created) n.createdMs else n.editedMs

    /**
     * "Today", "Yesterday", "Previous 7 days", "Earlier this month", then "October" (this year) or "October 2025".
     */
    fun groupLabel(ms: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        fun startOfDay(t: Long) = Calendar.getInstance(tz).apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val today = startOfDay(now)
        val day = startOfDay(ms)
        val daysAgo = Math.round((today.timeInMillis - day.timeInMillis) / DAY.toDouble()).toInt()
        val sameMonth = today.get(Calendar.YEAR) == day.get(Calendar.YEAR) && today.get(Calendar.MONTH) == day.get(Calendar.MONTH)
        return when {
            daysAgo <= 0 -> "Today"
            daysAgo == 1 -> "Yesterday"
            daysAgo <= 7 -> "Previous 7 days"
            sameMonth -> "Earlier this month"
            else -> SimpleDateFormat(if (today.get(Calendar.YEAR) == day.get(Calendar.YEAR)) "MMMM" else "MMMM yyyy", Locale.ENGLISH)
                .apply { timeZone = tz }.format(day.time)
        }
    }

    /** Consecutive sections in list order: (label, notes). */
    fun groups(notes: List<Note>, sort: NoteSort, now: Long, tz: TimeZone = TimeZone.getDefault()): List<Pair<String, List<Note>>> {
        val out = ArrayList<Pair<String, MutableList<Note>>>()
        for (n in notes) {
            val label = groupLabel(dateOf(n, sort), now, tz)
            if (out.isEmpty() || out.last().first != label) out += label to mutableListOf(n) else out.last().second += n
        }
        return out
    }
}

/** Nested folders as a flat, indented list for the side menu. */
object FolderTree {
    data class Row(val folder: Folder, val depth: Int)

    fun rows(folders: List<Folder>): List<Row> {
        val ids = folders.map { it.id }.toSet()
        val kids = folders.groupBy { f -> f.parentId?.takeIf { it in ids } }   // missing parent → top level
        val out = ArrayList<Row>()
        fun walk(parent: String?, depth: Int, seen: Set<String>) {
            for (f in kids[parent].orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })) {
                if (f.id in seen) continue                                         // a cycle from bad sync data
                out += Row(f, depth)
                walk(f.id, depth + 1, seen + f.id)
            }
        }
        walk(null, 0, emptySet())
        return out
    }

    fun descendants(folders: List<Folder>, id: String): List<String> {
        val out = ArrayList<String>()
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (f in folders) if (f.parentId == p && f.id !in out && f.id != id) { out += f.id; queue += f.id }
        }
        return out
    }

    /** "Work › Clients" for a folder. */
    fun path(folders: List<Folder>, id: String?): String {
        if (id == null) return ""
        val byId = folders.associateBy { it.id }
        val names = ArrayList<String>()
        var cur = byId[id]
        while (cur != null && names.size < 10) { names.add(0, cur.name); cur = cur.parentId?.let { byId[it] } }
        return names.joinToString(" › ")
    }
}

/** Note ↔ sync item. title/body/raw/pinned/created_ms as before (old clients read those); the rest is optional. */
object NoteSync {
    const val MAX_BYTES = 60_000   // the server's item limit is 64 KB

    fun toJson(n: Note): JSONObject {
        var raw = n.raw
        var body = n.body
        fun build() = JSONObject().apply {
            put("title", n.title); put("body", body); put("raw", raw)
            put("pinned", n.pinned); put("created_ms", n.createdMs)
            put("folderId", n.folderId ?: JSONObject.NULL); put("locked", n.locked)
            put("binnedAt", n.binnedAt ?: JSONObject.NULL); put("editedAt", n.editedMs)
        }
        var o = build()
        // Too big for one item: the original words go first, then the body is cut (the phone keeps the full note).
        if (o.toString().toByteArray().size > MAX_BYTES) { raw = ""; o = build() }
        val over = o.toString().toByteArray().size - MAX_BYTES
        if (over > 0) { body = fitBody(body, over); o = build() }
        return o
    }

    fun fitBody(body: String, overBytes: Int): String {
        var b = body
        val note = "\n\n(Cut to fit sync: the full note is on the phone that wrote it.)"
        while (b.isNotEmpty() && (body.toByteArray().size - b.toByteArray().size) < overBytes + note.toByteArray().size + 64) {
            b = b.dropLast(maxOf(1, b.length / 20))
        }
        return b.trimEnd() + note
    }

    fun fromJson(id: String, updatedAt: Long, d: JSONObject, local: Note?): Note = Note(
        id, d.optString("title", ""), d.optString("body", ""), d.optString("raw", ""), local?.prevBody,
        d.optBoolean("pinned", false), d.optLong("created_ms", updatedAt), updatedAt,
        folderId = if (d.isNull("folderId")) null else d.optString("folderId").ifEmpty { null },
        locked = d.optBoolean("locked", false),
        binnedAt = if (d.isNull("binnedAt") || !d.has("binnedAt")) null else d.optLong("binnedAt"),
        audio = local?.audio ?: emptyList(),                 // recordings never sync: keep this phone's
        editedMs = d.optLong("editedAt", updatedAt),
    )
}

/**
 * The recordings of the last [KEEP] dictations (filesDir/recordings), linked from their History rows, so a
 * dictation can be played again or redone with another model. Older ones are deleted automatically.
 */
object RecordingStore {
    const val KEEP = 15
    private const val ORPHAN_MS = 30 * 60_000L

    fun dir(filesDir: File) = File(filesDir, "recordings").apply { mkdirs() }

    /** Moves a finished recording into the store; returns its new path (null if it could not be kept). */
    fun keep(filesDir: File, file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        val dst = File(dir(filesDir), "rec-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.m4a")
        return if (file.renameTo(dst) || (runCatching { file.copyTo(dst, true); file.delete() }.isSuccess && dst.exists())) dst.path else null
    }

    /** Which History rows lose their recording: everything after the newest [keep]. */
    fun toPrune(rows: List<Pair<String, Long>>, keep: Int = KEEP): List<String> =
        rows.sortedByDescending { it.second }.drop(keep).map { it.first }

    /** Applies the rule: deletes old recordings and files nothing points to any more. */
    fun prune(db: Db, filesDir: File, now: Long = System.currentTimeMillis()) {
        val rows = db.historyRecordings()
        val gone = toPrune(rows.map { it.first to it.second }).toSet()
        for ((id, _, path) in rows) if (id in gone) { File(path).delete(); db.clearHistoryAudio(id) }
        val linked = rows.filter { it.first !in gone }.map { File(it.third).name }.toSet()
        dir(filesDir).listFiles()?.forEach { f -> if (f.name !in linked && now - f.lastModified() > ORPHAN_MS) f.delete() }
    }
}

/** The editor's formatting bar on plain-text notes: line kinds ("- [ ] ", "- ", "1. ") and **bold**. */
object NoteFormat {
    enum class Kind { Check, Bullet, Number }

    private val PREFIX = Regex("""^(\s*)(- \[[ xX]] |[-*•] |\d{1,3}[.)] )""")

    data class Edit(val text: String, val selStart: Int, val selEnd: Int)

    /**
     * Turns the selected lines into [kind] lines; when they all already are, turns them back into plain lines.
     * Numbers continue from a numbered line just above.
     */
    fun toggleLines(text: String, selStart: Int, selEnd: Int, kind: Kind): Edit {
        val lines = text.split("\n").toMutableList()
        // line index for a character offset
        fun lineAt(off: Int): Int {
            var pos = 0
            for (i in lines.indices) { if (off <= pos + lines[i].length) return i; pos += lines[i].length + 1 }
            return lines.size - 1
        }
        val a = lineAt(minOf(selStart, selEnd)); val b = lineAt(maxOf(selStart, selEnd))
        fun isKind(l: String) = when (kind) {
            Kind.Check -> Regex("""^\s*- \[[ xX]] """).containsMatchIn(l)
            Kind.Bullet -> Regex("""^\s*[-*•] (?!\[)""").containsMatchIn(l)
            Kind.Number -> Regex("""^\s*\d{1,3}[.)] """).containsMatchIn(l)
        }
        val allAre = (a..b).all { isKind(lines[it]) }
        var number = if (a > 0) Regex("""^\s*(\d{1,3})[.)] """).find(lines[a - 1])?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
        var delta = 0
        var deltaFirst = 0
        for (i in a..b) {
            val m = PREFIX.find(lines[i])
            val indent = m?.groupValues?.get(1) ?: lines[i].takeWhile { it == ' ' }
            val rest = if (m != null) lines[i].substring(m.value.length) else lines[i].substring(indent.length)
            val prefix = if (allAre) "" else when (kind) { Kind.Check -> "- [ ] "; Kind.Bullet -> "- "; Kind.Number -> "${++number}. " }
            val new = indent + prefix + rest
            val d = new.length - lines[i].length
            if (i == a) deltaFirst = d
            delta += d
            lines[i] = new
        }
        val out = lines.joinToString("\n")
        val s = (minOf(selStart, selEnd) + deltaFirst).coerceIn(0, out.length)
        val e = (maxOf(selStart, selEnd) + delta).coerceIn(s, out.length)
        return Edit(out, s, e)
    }

    /** Wraps the selection in ** (or unwraps it); with no selection inserts **** and puts the cursor inside. */
    fun toggleBold(text: String, selStart: Int, selEnd: Int): Edit {
        val s = minOf(selStart, selEnd); val e = maxOf(selStart, selEnd)
        if (s >= 2 && e + 2 <= text.length && text.substring(s - 2, s) == "**" && text.substring(e, e + 2) == "**") {
            return Edit(text.removeRange(e, e + 2).removeRange(s - 2, s), s - 2, e - 2)
        }
        return Edit(text.substring(0, s) + "**" + text.substring(s, e) + "**" + text.substring(e), s + 2, e + 2)
    }

    /** Enter on a list line continues the list; Enter on an empty list line ends it (as in Samsung Notes). */
    fun continueList(old: String, new: String, cursor: Int): Edit? {
        if (new.length != old.length + 1 || cursor <= 0 || cursor > new.length || new[cursor - 1] != '\n') return null
        val lineStart = new.lastIndexOf('\n', cursor - 2) + 1
        val prev = new.substring(lineStart, cursor - 1)
        val m = PREFIX.find(prev) ?: return null
        val indent = m.groupValues[1]
        if (prev.substring(m.value.length).isBlank()) {
            // empty item: remove the marker and the new line
            val t = new.substring(0, lineStart) + indent + new.substring(cursor)
            return Edit(t, lineStart + indent.length, lineStart + indent.length)
        }
        val marker = m.groupValues[2]
        val next = when {
            marker.startsWith("- [") -> "- [ ] "
            marker[0].isDigit() -> "${(marker.takeWhile { it.isDigit() }.toIntOrNull() ?: 0) + 1}${marker.dropWhile { it.isDigit() }}"
            else -> marker
        }
        val ins = indent + next
        return Edit(new.substring(0, cursor) + ins + new.substring(cursor), cursor + ins.length, cursor + ins.length)
    }
}

/** "1 note", "2 notes", "1,240 words": singular for exactly one. */
fun counted(n: Int, one: String, many: String = one + "s"): String = "%,d %s".format(n, if (n == 1) one else many)
