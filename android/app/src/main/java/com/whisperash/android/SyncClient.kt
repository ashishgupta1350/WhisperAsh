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
        try { db.dirtyCount() + if (settings.settingsDirty) 1 else 0 } catch (_: Exception) { 0 },
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
        if (settings.userId.isNotEmpty() && settings.userId != id) db.markAllDirty()
        secure.put(SecureStore.SYNC_TOKEN, data.getString("token"))
        settings.email = user.getString("email")
        settings.userId = id
        settings.cursorJson = null
        settings.lastSyncError = ""
        refreshState()
        syncNow(Reason.LOGIN)
    }

    suspend fun logout() {
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
        const val PULL_OVERLAP_MS = 10_000L
    }
}
