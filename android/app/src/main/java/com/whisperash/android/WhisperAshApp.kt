package com.whisperash.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        groq = GroqClient(http) { secure.get(SecureStore.GROQ_KEY) }
        gemini = GeminiClient(http) { secure.get(SecureStore.GEMINI_KEY) }
        sync = SyncClient(http, settings, secure, db, scope)
        engine = DictationEngine(this)
        notes = NoteMaker(this)
        settings.onSyncedChange = { sync.requestSync(SyncClient.Reason.CHANGE) }
    }

    companion object {
        lateinit var instance: WhisperAshApp
            private set
    }
}
