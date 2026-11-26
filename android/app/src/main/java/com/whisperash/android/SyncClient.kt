package com.whisperash.android

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Words the account may dictate on the owner's keys (from the server's /me). */
data class AccountPlan(
    val limit: Int, val used: Int, val left: Int, val period: String, val resetsAt: Long,
    val status: String, val ownGroqKey: Boolean,
    /** Meeting recorder minutes on the owner's keys, IST day; -1 = the server did not say. */
    val meetingLimitMin: Int = -1, val meetingUsedMin: Int = -1, val meetingLeftMin: Int = -1,
) {
    val periodWord get() = if (period == "week") "this week" else "today"
    /** "1,240 of 2,000 words left today" / "No limit: your own key" / "Paused by the admin". */
    fun summary(): String = when {
        status == "blocked" -> "Your account is paused. Contact the WhisperAsh admin."
        ownGroqKey -> "No word limit: your account uses a key the admin set for you."
        else -> "%,d of %,d words left %s".format(left, limit, periodWord)
    }

    /** "87 min left today" for small spaces (Home's Meetings card), or null when the server sent no limit. */
    fun meetingShort(): String? = when {
        status == "blocked" -> "Account paused"
        meetingLimitMin < 0 || meetingLeftMin < 0 -> null
        else -> "%,d min left today".format(meetingLeftMin)
    }

    /** "87 of 120 meeting minutes left today", or null when the server sent no meeting limit. */
    fun meetingSummary(): String? = when {
        status == "blocked" -> "Your account is paused. Contact the WhisperAsh admin."
        meetingLimitMin < 0 || meetingLeftMin < 0 -> null
        else -> "%,d of %,d meeting minutes left today".format(meetingLeftMin, meetingLimitMin)
    }
}

data class SyncState(
    val signedIn: Boolean,
    val email: String,
    val busy: Boolean,
    val lastSyncAt: Long,
    val lastError: String,
    val pending: Int,
)

/**
 * Account + sync against the WhisperAsh Cloudflare Worker (worker/src/index.js), same protocol
 * as the desktop client (app/src/helpers/whisperashSync.js): push dirty rows (<=200 per call),
 * pull from a cursor with a 10 s overlap, last write wins by updated_at.
 */
class SyncClient(
    private val http: OkHttpClient,
    private val settings: Settings,
    private val secure: SecureStore,
    private val db: Db,
    private val scope: CoroutineScope,
) {
    enum class Reason { CHANGE, OPEN, MANUAL, LOGIN }

    private val mutex = Mutex()
    private var debounce: Job? = null
    private val _state = MutableStateFlow(snapshot(false))
    val state: StateFlow<SyncState> = _state
    /** Bumped whenever pulled data changed history / dictionary / settings, so screens refresh. */
    val dataVersion = MutableStateFlow(0)

    val signedIn get() = settings.email.isNotEmpty() && secure.has(SecureStore.SYNC_TOKEN)

    private fun snapshot(busy: Boolean) = SyncState(
        signedIn, settings.email, busy, settings.lastSyncAt, settings.lastSyncError,
        try { db.dirtyCount() + db.dirtyNoteCount() + db.dirtyMeetingCount(settings.userId) + if (settings.settingsDirty) 1 else 0 } catch (_: Exception) { 0 },
    )

    fun refreshState() { _state.value = snapshot(_state.value.busy) }

    // ---------- HTTP ----------
    private fun request(path: String, body: JSONObject?, auth: Boolean = true): JSONObject {
        val base = settings.serverUrl.trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) { "Set the sync server URL first" }
        val b = Request.Builder().url(base + path)
            .post((body ?: JSONObject()).toString().toRequestBody(GroqClient.JSON))
        if (auth) {
            val token = secure.get(SecureStore.SYNC_TOKEN) ?: throw HttpError(401, "Not signed in")
            b.header("Authorization", "Bearer $token")
        }
        val res = try {
            http.newCall(b.build()).execute()
        } catch (e: IOException) {
            throw HttpError(0, "Offline: changes are saved and will sync later")
        }
        res.use {
            val text = it.body.string()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (!it.isSuccessful) throw HttpError(it.code, json.optString("error", "HTTP ${it.code}"))
            return json
        }
    }

    class HttpError(val status: Int, message: String) : IOException(message)

    /** The account's word allowance (`plan` from GET /me), or null when signed out or offline. */
    fun fetchPlan(): AccountPlan? {
        if (!signedIn) return null
        val token = secure.get(SecureStore.SYNC_TOKEN) ?: return null
        val req = Request.Builder().url(settings.serverUrl.trimEnd('/') + "/me").get()
            .header("Authorization", "Bearer $token").build()
        return try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val p = JSONObject(res.body.string()).optJSONObject("plan") ?: return null
                AccountPlan(
                    p.optInt("limit"), p.optInt("used"), p.optInt("left"), p.optString("period", "day"),
                    p.optLong("resetsAt"), p.optString("status", "active"),
                    p.optJSONObject("ownKeys")?.optBoolean("groq") == true,
                    p.optInt("meetingLimitMin", -1), p.optInt("meetingUsedMin", -1), p.optInt("meetingLeftMin", -1),
                ).also { lastPlan = it }
            }
        } catch (_: Exception) {
            null
        }
    }

    @Volatile var lastPlan: AccountPlan? = null
        private set

    private fun device() = "Android ${Build.MANUFACTURER} ${Build.MODEL}".take(100)

    // ---------- account ----------
    suspend fun login(email: String, password: String) = afterLogin(
        request("/auth/login", JSONObject().put("email", email.trim()).put("password", password).put("device", device()), auth = false)
    )

    suspend fun register(email: String, password: String, signupCode: String) = afterLogin(
        request(
            "/auth/register",
            JSONObject().put("email", email.trim()).put("password", password)
                .put("signupCode", signupCode.trim().ifEmpty { null } ?: JSONObject.NULL).put("device", device()),
            auth = false,
        )
    )

    private suspend fun afterLogin(data: JSONObject) {
        val user = data.getJSONObject("user")
        val id = user.getString("id")
        // A different account than before: offer everything local to it, pull its data in full.
        mutex.withLock {
            if (settings.userId.isNotEmpty() && settings.userId != id) db.markAllDirty()
            if (settings.userId.isEmpty()) db.claimLocalDiaries(id)
            secure.put(SecureStore.SYNC_TOKEN, data.getString("token"))
            settings.email = user.getString("email")
            settings.userId = id
            settings.cursorJson = null
            settings.lastSyncError = ""
            dataVersion.value++
            refreshState()
        }
        syncNow(Reason.LOGIN)
    }

    /** "Forgot password": the admin sees the request and sends a temporary password (no email service). */
    fun requestPasswordReset(email: String) {
        request("/auth/forgot", JSONObject().put("email", email.trim()), auth = false)
    }

    /** Signed in: replace the password. The server signs out the user's other devices. */
    fun changePassword(current: String, newPassword: String) {
        request("/auth/change-password", JSONObject().put("current", current).put("password", newPassword))
    }

    suspend fun logout() = mutex.withLock {
        try { request("/auth/logout", null) } catch (_: Exception) {}
        secure.put(SecureStore.SYNC_TOKEN, null)
        settings.email = ""
        refreshState()
    }

    // ---------- scheduling ----------
    fun requestSync(reason: Reason) {
        if (!signedIn) return
        debounce?.cancel()
        debounce = scope.launch {
            // After a dictation, wait so a sync never shares the network with the next one.
            if (reason == Reason.CHANGE) delay(4_000)
            if (reason == Reason.OPEN && System.currentTimeMillis() - settings.lastSyncAt < 180_000) return@launch
            try { syncNow(reason) } catch (_: Exception) {}
        }
    }

    suspend fun syncNow(reason: Reason) = mutex.withLock {
        if (!signedIn) return@withLock
        _state.value = snapshot(true)
        try {
            push()
            pushFolders(force = reason == Reason.MANUAL || reason == Reason.LOGIN)
            pushNotes(force = reason == Reason.MANUAL || reason == Reason.LOGIN)
            val force = reason == Reason.MANUAL || reason == Reason.LOGIN
            pushRecordings(RecordingKind.MEETING, force)
            pushRecordings(RecordingKind.DIARY, force)
            pull()
            settings.lastSyncAt = System.currentTimeMillis()
            settings.lastSyncError = ""
            Log.i(TAG, "sync done ($reason)")
        } catch (e: Exception) {
            if (e is HttpError && e.status == 401) {
                secure.put(SecureStore.SYNC_TOKEN, null)
                settings.lastSyncError = "Session expired: please log in again"
            } else {
                settings.lastSyncError = e.message ?: e.javaClass.simpleName
            }
            Log.w(TAG, "sync failed ($reason): ${e.message}")
        } finally {
            _state.value = snapshot(false)
        }
    }

    private fun canPushSettings() = settings.userId.isNotEmpty() && settings.settingsPulledFor == settings.userId

    private fun push() {
        while (true) {
            val rows = db.dirtyItems(PUSH_BATCH).toMutableList()
            val withSettings = rows.size < PUSH_BATCH && settings.settingsDirty && canPushSettings()
            if (rows.isEmpty() && !withSettings) return
            val items = JSONArray()
            for (r in rows) items.put(
                JSONObject().put("kind", r.kind).put("id", r.id).put("updated_at", r.updatedAt)
                    .put("deleted", r.deleted).put("data", r.data)
            )
            val settingsTs = settings.settingsUpdatedAt
            if (withSettings) items.put(
                JSONObject().put("kind", "settings").put("id", "settings").put("updated_at", settingsTs)
                    .put("deleted", false).put("data", Settings.toJson(settings.syncedMap()))
            )
            request("/sync/push", JSONObject().put("items", items))
            db.markClean(rows)
            if (withSettings && settings.settingsUpdatedAt == settingsTs) settings.settingsDirty = false
        }
    }

    /** Folders go alone too: the server needs the "folders" kind; until then they stay on this phone (400 → retry in 6 h). */
    private fun pushFolders(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - settings.foldersUnsupportedAt < 6 * 3_600_000L) return
        while (true) {
            val rows = db.dirtyFolders(PUSH_BATCH)
            if (rows.isEmpty()) return
            val items = JSONArray()
            for (r in rows) items.put(
                JSONObject().put("kind", r.kind).put("id", r.id).put("updated_at", r.updatedAt)
                    .put("deleted", r.deleted).put("data", r.data)
            )
            try {
                request("/sync/push", JSONObject().put("items", items))
            } catch (e: HttpError) {
                if (e.status == 400) {
                    settings.foldersUnsupportedAt = System.currentTimeMillis()
                    Log.w(TAG, "sync server does not take folders yet; kept on this phone")
                    return
                }
                throw e
            }
            settings.foldersUnsupportedAt = 0
            db.markFoldersClean(rows)
        }
    }

    /**
     * Notes go in their own upload: a sync server that does not know the "notes" kind yet answers 400,
     * which must never block history / dictionary sync. The notes then wait on the phone and are retried
     * every 6 hours (or right away from Sync now).
     */
    private fun pushNotes(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - settings.notesUnsupportedAt < 6 * 3_600_000L) return
        while (true) {
            val rows = db.dirtyNotes(PUSH_BATCH)
            if (rows.isEmpty()) return
            val items = JSONArray()
            for (r in rows) items.put(
                JSONObject().put("kind", r.kind).put("id", r.id).put("updated_at", r.updatedAt)
                    .put("deleted", r.deleted).put("data", r.data)
            )
            try {
                request("/sync/push", JSONObject().put("items", items))
            } catch (e: HttpError) {
                if (e.status == 400) {
                    settings.notesUnsupportedAt = System.currentTimeMillis()
                    Log.w(TAG, "sync server does not take notes yet; kept on this phone")
                    return
                }
                throw e
            }
            settings.notesUnsupportedAt = 0
            db.markNotesClean(rows)
        }
    }

    /** Meetings, like notes, go in their own upload so an older server's 400 never blocks the rest. Few per call: each can be ~500 KB. */
    private fun pushRecordings(kind: RecordingKind, force: Boolean) {
        val unsupportedAt = if (kind.isDiary) settings.diariesUnsupportedAt else settings.meetingsUnsupportedAt
        fun updateSupport(at: Long) {
            if (kind.isDiary) settings.diariesUnsupportedAt = at else settings.meetingsUnsupportedAt = at
        }
        val owner = if (kind.isDiary) settings.userId.takeIf(String::isNotEmpty) ?: return else ""
        if (!force && System.currentTimeMillis() - unsupportedAt < 6 * 3_600_000L) return
        while (true) {
            val rows = db.dirtyMeetings(MEETING_BATCH, kind, owner)
            if (rows.isEmpty()) return
            val items = JSONArray()
            for (r in rows) items.put(
                JSONObject().put("kind", r.kind).put("id", r.id).put("updated_at", r.updatedAt)
                    .put("deleted", r.deleted).put("data", r.data)
            )
            try {
                request("/sync/push", JSONObject().put("items", items))
            } catch (e: HttpError) {
                if (e.status == 400 && e.message == "item_too_large") {
                    // One meeting is over the server's 512 KB: send the others alone, keep that one on this
                    // phone only (no endless retries; it is offered again if it changes).
                    for (r in rows) {
                        val one = JSONArray().put(
                            JSONObject().put("kind", r.kind).put("id", r.id).put("updated_at", r.updatedAt)
                                .put("deleted", r.deleted).put("data", r.data)
                        )
                        try {
                            request("/sync/push", JSONObject().put("items", one))
                        } catch (e1: HttpError) {
                            if (e1.status != 400 || e1.message != "item_too_large") throw e1
                            Log.w(TAG, "meeting ${r.id} is too large to sync; kept on this phone")
                        }
                        db.markMeetingsClean(listOf(r))
                    }
                    continue
                }
                if (e.status == 400) {
                    updateSupport(System.currentTimeMillis())
                    Log.w(TAG, "Sync server does not support ${kind.wireName} yet; kept on this phone")
                    return
                }
                throw e
            }
            updateSupport(0)
            db.markMeetingsClean(rows)
        }
    }

    private fun pull() {
        var cursor: JSONObject? = settings.cursorJson?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        var changed = false
        var gotSettings = false
        while (true) {
            val page = request("/sync/pull", JSONObject().put("cursor", cursor ?: JSONObject.NULL))
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val kind = it.getString("kind")
                val id = it.getString("id")
                val ts = it.getLong("updated_at")
                val deleted = it.optBoolean("deleted", false)
                val data = it.optJSONObject("data") ?: JSONObject()
                if (kind == "settings") {
                    gotSettings = true
                    if (applySettings(ts, deleted, data)) changed = true
                } else if (kind == "notes") {
                    if (db.applyRemoteNote(id, ts, deleted, data)) changed = true
                } else if (kind == "folders") {
                    if (db.applyRemoteFolder(id, ts, deleted, data)) changed = true
                } else if (kind == "meetings") {
                    if (db.applyRemoteMeeting(id, ts, deleted, data)) changed = true
                } else if (kind == RecordingKind.DIARY.wireName && settings.userId.isNotEmpty()) {
                    if (db.applyRemoteMeeting(id, ts, deleted, data, RecordingKind.DIARY, settings.userId)) changed = true
                } else if (db.applyRemote(kind, id, ts, deleted, data)) changed = true
            }
            cursor = page.optJSONObject("cursor")
            if (!page.optBoolean("more", false)) break
        }
        settings.cursorJson = cursor?.let {
            JSONObject().put("ts", maxOf(0L, it.optLong("ts", 0) - PULL_OVERLAP_MS)).put("kind", "").put("id", "").toString()
        }
        val firstPull = settings.settingsPulledFor != settings.userId
        settings.settingsPulledFor = settings.userId
        // First sync of this account here and the cloud had no settings: offer this phone's.
        if (firstPull && !gotSettings && settings.syncedMap().isNotEmpty()) {
            settings.settingsUpdatedAt = System.currentTimeMillis()
            settings.settingsDirty = true
            push()
        }
        if (changed) dataVersion.value++
    }

    private fun applySettings(ts: Long, deleted: Boolean, data: JSONObject): Boolean {
        if (deleted) return false
        val firstPull = settings.settingsPulledFor != settings.userId
        // Until the first pull, local values are only a baseline: the cloud copy wins.
        if (!firstPull) {
            if (settings.settingsDirty && settings.settingsUpdatedAt > ts) return false
            if (!settings.settingsDirty && settings.settingsUpdatedAt >= ts) return false
        }
        val m = mutableMapOf<String, String?>()
        for (k in data.keys()) m[k] = if (data.isNull(k)) null else data.get(k).toString()
        settings.replaceSynced(m)
        settings.settingsUpdatedAt = ts
        settings.settingsDirty = false
        return true
    }

    companion object {
        const val TAG = "WhisperAshSync"
        const val PUSH_BATCH = 200
        const val MEETING_BATCH = 4
        const val PULL_OVERLAP_MS = 10_000L
    }
}
