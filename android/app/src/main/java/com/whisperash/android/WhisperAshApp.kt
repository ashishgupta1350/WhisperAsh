package com.whisperash.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WhisperAshApp : Application() {
    lateinit var settings: Settings
    lateinit var secure: SecureStore
    lateinit var db: Db
    lateinit var http: OkHttpClient
    lateinit var groq: GroqClient
    lateinit var gemini: GeminiClient
    lateinit var sync: SyncClient
    lateinit var engine: DictationEngine
    lateinit var notes: NoteMaker
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        secure = SecureStore(this)
        db = Db(this)
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        groq = GroqClient(http) { groqTarget() }
        gemini = GeminiClient(http) { geminiTarget() }
        sync = SyncClient(http, settings, secure, db, scope)
        engine = DictationEngine(this)
        notes = NoteMaker(this)
        settings.onSyncedChange = { sync.requestSync(SyncClient.Reason.CHANGE) }
        // A new process means no meeting recorder is running: meetings a killed app left mid-way are failed.
        // Done before anything else can start a recording, so a new one is never caught by it.
        try { db.failUnfinishedMeetings() } catch (_: Exception) {}
        // Recycle bin keeps notes 15 days; only the last 15 dictations keep their recording.
        scope.launch {
            try { db.purgeBin() } catch (_: Exception) {}
            try { RecordingStore.prune(db, filesDir) } catch (_: Exception) {}
        }
    }

    /**
     * Account mode: dictation runs through the WhisperAsh server on the owner's keys (word limit).
     * Own-keys mode: the phone calls Groq/Gemini directly with keys saved here. Installs that already
     * had a Groq key keep using it until the user switches.
     */
    fun useAccount(): Boolean = when (settings.engineSource) {
        Settings.ENGINE_OWN -> false
        Settings.ENGINE_ACCOUNT -> true
        else -> !secure.has(SecureStore.GROQ_KEY)
    }

    private fun accountTarget(path: String): ApiTarget? {
        if (!sync.signedIn) return null
        val token = secure.get(SecureStore.SYNC_TOKEN) ?: return null
        val server = settings.serverUrl.trimEnd('/')
        return ApiTarget(server + path, token, true, "$server/")
    }

    fun groqTarget(): ApiTarget? =
        if (useAccount()) accountTarget("/groq")
        else secure.get(SecureStore.GROQ_KEY)?.let { ApiTarget(GroqClient.BASE, it, false, "${GroqClient.BASE}/") }

    fun geminiTarget(): ApiTarget? =
        if (useAccount()) accountTarget("/gemini")
        else secure.get(SecureStore.GEMINI_KEY)?.let { ApiTarget(GeminiClient.BASE, it, false, "${GeminiClient.BASE}/") }

    /** Ready to dictate: signed in (account mode) or an own key saved. */
    fun canDictate(): Boolean = groqTarget() != null || geminiTarget() != null

    /**
     * Meeting recorder, per provider: this phone's own key for it first (direct), else the WhisperAsh
     * account (meeting-minutes limit), else none. Not tied to [useAccount], which is for dictation.
     */
    fun meetingTarget(provider: String): ApiTarget? {
        val own = when (provider) {
            MeetingRoutes.GEMINI -> SecureStore.GEMINI_KEY
            MeetingRoutes.DEEPGRAM -> SecureStore.DEEPGRAM_KEY
            else -> SecureStore.GROQ_KEY
        }
        val token = if (sync.signedIn) secure.get(SecureStore.SYNC_TOKEN) else null
        return MeetingRoutes.pick(provider, secure.get(own), token, settings.serverUrl)
    }

    fun deepgramTarget(): ApiTarget? = meetingTarget(MeetingRoutes.DEEPGRAM)

    fun canRecordMeetings(): Boolean =
        listOf(MeetingRoutes.GEMINI, MeetingRoutes.DEEPGRAM, MeetingRoutes.GROQ).any { meetingTarget(it) != null }


    companion object {
        lateinit var instance: WhisperAshApp
            private set
    }
}
