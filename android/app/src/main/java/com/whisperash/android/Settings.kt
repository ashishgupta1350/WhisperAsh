package com.whisperash.android

import android.content.Context
import org.json.JSONObject

/**
 * Settings. Two kinds:
 *  - synced: the same keys and string values as the desktop app's localStorage
 *    (see app/src/utils/whisperashSettingsSync.ts), kept as one map so keys this
 *    app does not use (snippets, customPrompts...) survive a round trip untouched.
 *  - local: per-phone things (mic mode, haptics, sync cursor...).
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var onSyncedChange: (() -> Unit)? = null

    // ---------- synced ----------
    @Synchronized
    fun syncedMap(): MutableMap<String, String?> {
        val raw = prefs.getString("synced", null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, String?>()
            for (k in o.keys()) m[k] = if (o.isNull(k)) null else o.get(k).toString()
            m
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    @Synchronized
    fun replaceSynced(values: Map<String, String?>) {
        prefs.edit().putString("synced", toJson(values).toString()).apply()
    }

    fun setSynced(key: String, value: String?) {
        val m = syncedMap()
        if (m[key] == value && m.containsKey(key)) return
        m[key] = value
        replaceSynced(m)
        settingsUpdatedAt = System.currentTimeMillis()
        settingsDirty = true
        onSyncedChange?.invoke()
    }

    private fun s(key: String) = syncedMap()[key]

    val language: String get() = s("preferredLanguage")?.takeIf { it.isNotBlank() } ?: "auto"
    val hindiScript: String
        get() = s("hindiScriptPreference")?.takeIf { it in HINDI_SCRIPTS } ?: "roman"

    /** Groq Whisper model; anything the phone can't run falls back to whisper-large-v3. */
    val sttModel: String
        get() = s("cloudTranscriptionModel")?.takeIf { it in GROQ_STT_MODELS } ?: DEFAULT_STT_MODEL

    val cleanupModel: String
        get() = s("cleanupModel")?.takeIf { it in GROQ_CLEANUP_MODELS } ?: DEFAULT_CLEANUP_MODEL

    val useCleanup: Boolean get() = s("useCleanupModel") != "false"
    val toneEnabled: Boolean get() = s("toneStyleEnabled") == "true"
    val agentName: String get() = s("agentName")?.takeIf { it.isNotBlank() } ?: "Assistant"

    /** personal / work / email / other -> formal / casual / very_casual / excited */
    val toneStyles: Map<String, String>
        get() {
            val out = Prompts.DEFAULT_TONES.toMutableMap()
            try {
                val o = JSONObject(s("toneStyles") ?: "{}")
                for (cat in Prompts.TONE_OPTIONS.keys) {
                    val v = o.optString(cat, "")
                    if (v in Prompts.TONE_OPTIONS.getValue(cat)) out[cat] = v
                }
            } catch (_: Exception) {
            }
            return out
        }

    fun setTone(category: String, tone: String) {
        val m = toneStyles.toMutableMap()
        m[category] = tone
        setSynced("toneStyles", JSONObject(m as Map<*, *>).toString())
    }

    var settingsUpdatedAt: Long
        get() = prefs.getLong("settings_updated_at", 0)
        set(v) = prefs.edit().putLong("settings_updated_at", v).apply()
    var settingsDirty: Boolean
        get() = prefs.getBoolean("settings_dirty", false)
        set(v) = prefs.edit().putBoolean("settings_dirty", v).apply()

    // ---------- local ----------
    var holdToTalk: Boolean
        get() = prefs.getBoolean("hold_to_talk", false)
        set(v) = prefs.edit().putBoolean("hold_to_talk", v).apply()
    var typingMode: Boolean
        get() = prefs.getBoolean("typing_mode", false)
        set(v) = prefs.edit().putBoolean("typing_mode", v).apply()
    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()
    var geminiBackup: Boolean
        get() = prefs.getBoolean("gemini_backup", true)
        set(v) = prefs.edit().putBoolean("gemini_backup", v).apply()
    /** ENGINE_ACCOUNT, ENGINE_OWN, or null = decided by whether an own Groq key is saved. Per phone. */
    var engineSource: String?
        get() = prefs.getString("engine_source", null)
        set(v) = prefs.edit().putString("engine_source", v).apply()
    var autoSpace: Boolean
        get() = prefs.getBoolean("auto_space", true)
        set(v) = prefs.edit().putBoolean("auto_space", v).apply()

    var serverUrl: String
        get() = prefs.getString("server_url", null) ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString("server_url", v.trim()).apply()
    var email: String
        get() = prefs.getString("email", "") ?: ""
        set(v) = prefs.edit().putString("email", v).apply()
    var userId: String
        get() = prefs.getString("user_id", "") ?: ""
        set(v) = prefs.edit().putString("user_id", v).apply()
    var cursorJson: String?
        get() = prefs.getString("cursor", null)
        set(v) = prefs.edit().putString("cursor", v).apply()
    var settingsPulledFor: String
        get() = prefs.getString("settings_pulled_for", "") ?: ""
        set(v) = prefs.edit().putString("settings_pulled_for", v).apply()
    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0)
        set(v) = prefs.edit().putLong("last_sync_at", v).apply()
    /** When the sync server last refused notes (it predates them); 0 = notes sync fine. */
    var notesUnsupportedAt: Long
        get() = prefs.getLong("notes_unsupported_at", 0)
        set(v) = prefs.edit().putLong("notes_unsupported_at", v).apply()
    /** Same as [notesUnsupportedAt], for folders (the server needs the "folders" kind). */
    var foldersUnsupportedAt: Long
        get() = prefs.getLong("folders_unsupported_at", 0)
        set(v) = prefs.edit().putLong("folders_unsupported_at", v).apply()
    /** Notes screen: Grid / List / Simple list, sort, and "Group by date" (Samsung Notes' view options). */
    var notesLayout: String
        get() = prefs.getString("notes_layout", "Grid") ?: "Grid"
        set(v) = prefs.edit().putString("notes_layout", v).apply()
    var notesSort: String
        get() = prefs.getString("notes_sort", "Modified") ?: "Modified"
        set(v) = prefs.edit().putString("notes_sort", v).apply()
    var notesGroupByDate: Boolean
        get() = prefs.getBoolean("notes_group_by_date", false)
        set(v) = prefs.edit().putBoolean("notes_group_by_date", v).apply()
    /** Same as [notesUnsupportedAt], for meetings. */
    var meetingsUnsupportedAt: Long
        get() = prefs.getLong("meetings_unsupported_at", 0)
        set(v) = prefs.edit().putLong("meetings_unsupported_at", v).apply()
    /** Android 13+: the notification permission is asked once, on the first meeting recording. */
    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(v) = prefs.edit().putBoolean("asked_notifications", v).apply()
    /** The Home "Finish setting up" card was closed; Settings then offers "Setup checklist" to bring it back. */
    var setupDismissed: Boolean
        get() = prefs.getBoolean("setup_dismissed", false)
        set(v) = prefs.edit().putBoolean("setup_dismissed", v).apply()
    var lastSyncError: String
        get() = prefs.getString("last_sync_error", "") ?: ""
        set(v) = prefs.edit().putString("last_sync_error", v).apply()

    companion object {
        const val DEFAULT_SERVER = "https://whisperash-sync.whisperash-ashish.workers.dev"
        const val ENGINE_ACCOUNT = "account"
        const val ENGINE_OWN = "own"
        const val DEFAULT_STT_MODEL = "whisper-large-v3"
        const val PRECISE_MODEL = "openai/gpt-oss-120b"
        const val FASTER_MODEL = "qwen/qwen3.8-27b"
        const val DEFAULT_CLEANUP_MODEL = FASTER_MODEL
        const val FALLBACK_CLEANUP_MODEL = "openai/gpt-oss-20b"
        val GROQ_STT_MODELS = setOf("whisper-large-v3", "whisper-large-v3-turbo")
        val GROQ_CLEANUP_MODELS = setOf("qwen/qwen3.8-27b", "openai/gpt-oss-20b", "openai/gpt-oss-120b")
        val HINDI_SCRIPTS = listOf("roman", "devanagari", "english")
        val LANGUAGES = listOf("auto" to "Auto-detect", "en" to "English", "hi" to "Hindi")

        fun toJson(values: Map<String, String?>): JSONObject {
            val o = JSONObject()
            for ((k, v) in values) o.put(k, v ?: JSONObject.NULL)
            return o
        }
    }
}
