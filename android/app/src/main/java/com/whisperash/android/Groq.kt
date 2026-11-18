package com.whisperash.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Groq free-tier limits (console.groq.com/docs/rate-limits, checked 2026-11-15). */
object GroqLimits {
    const val STT_RPM = 20
    const val STT_RPD = 2000
    const val AUDIO_PER_HOUR = 7200
    const val AUDIO_PER_DAY = 28800
    const val MIN_BILLED_SECONDS = 10
    const val LLM_RPM = 30
    const val LLM_RPD = 1000
    const val LLM_TPD = 200_000
}

class ApiException(val status: Int, val provider: String, message: String, val code: String? = null) : IOException(message) {
    /** The WhisperAsh account said no (limit reached, paused, signed out): no backup engine will help. */
    val final get() = code in FINAL_CODES
    /** Worth trying the backup provider: rate limit, server trouble, or no answer. */
    val retryable get() = !final && (status == 0 || status == 408 || status == 429 || status >= 500)

    companion object {
        val FINAL_CODES = setOf("word_limit", "chat_limit", "account_blocked", "session_expired")
    }
}

/**
 * Where a request goes: the WhisperAsh account gateway (the sync server proxies to the owner's
 * Groq/Gemini keys, with a word limit) or the provider directly with the user's own key.
 */
data class ApiTarget(val base: String, val token: String, val viaAccount: Boolean, val warmUrl: String)

data class SttResult(val text: String, val language: String?, val noSpeechProb: Double, val model: String, val provider: String)
data class CleanupResult(val text: String, val model: String, val tokens: Int)

class GroqClient(private val http: OkHttpClient, private val target: () -> ApiTarget?) {
    // Cleanup must never hold the text back for long: past 15 s the raw transcript is used.
    private val chatHttp = http.newBuilder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    private val sttHttp = http.newBuilder().callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    /** Last x-ratelimit-* headers Groq sent, for the usage screen. */
    @Volatile var lastRateHeaders: Map<String, String> = emptyMap()
        private set
    /** Words left on the WhisperAsh account after the last request (account mode only). */
    @Volatile var lastWordsLeft: Int? = null
        private set

    /** Opens the TLS connection while the user is still talking (no key sent). */
    fun warmUp() {
        try {
            val url = target()?.warmUrl ?: "$BASE/"
            http.newCall(Request.Builder().url(url).head().build()).execute().close()
        } catch (_: Exception) {
        }
    }

    fun transcribe(audio: File, mime: String, model: String, language: String, prompt: String): SttResult {
        val t = target() ?: throw notSetUp()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody(mime.toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .apply {
                if (language != "auto") addFormDataPart("language", language)
                if (prompt.isNotBlank()) addFormDataPart("prompt", prompt)
            }
            .build()
        val req = Request.Builder().url("${t.base}/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${t.token}").post(body).build()
        val json = call(sttHttp, req, t)
        var noSpeech = 0.0
        json.optJSONArray("segments")?.let { segs ->
            if (segs.length() > 0) {
                var sum = 0.0
                for (i in 0 until segs.length()) sum += segs.getJSONObject(i).optDouble("no_speech_prob", 0.0)
                noSpeech = sum / segs.length()
            }
        }
        return SttResult(json.optString("text", "").trim(), json.optString("language", "").ifEmpty { null }, noSpeech, model, "groq")
    }

    fun chat(model: String, system: String, user: String, maxTokens: Int): CleanupResult {
        val t = target() ?: throw notSetUp()
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
            put("temperature", 0)
            put("max_completion_tokens", maxTokens)
            // Thinking off (qwen) or as low as it goes (gpt-oss), like the desktop cleanup.
            put("reasoning_effort", if (model.contains("qwen")) "none" else "low")
            if (model.contains("gpt-oss")) put("include_reasoning", false)
        }
        val req = Request.Builder().url("${t.base}/openai/v1/chat/completions")
            .header("Authorization", "Bearer ${t.token}")
            .post(body.toString().toRequestBody(JSON)).build()
        val json = call(chatHttp, req, t)
        val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val tokens = json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
        return CleanupResult(msg.optString("content", ""), model, tokens)
    }

    private fun notSetUp() = ApiException(401, "Groq", "Sign in to WhisperAsh (Account tab) or add your own Groq key", "not_set_up")

    private fun call(client: OkHttpClient, req: Request, t: ApiTarget): JSONObject {
        val provider = if (t.viaAccount) "WhisperAsh" else "Groq"
        val res: Response = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw ApiException(0, provider, "No connection to $provider (${e.message ?: e.javaClass.simpleName})")
        }
        res.use {
            val headers = HashMap<String, String>()
            for ((name, value) in it.headers) if (name.lowercase().startsWith("x-ratelimit")) headers[name.lowercase()] = value
            if (headers.isNotEmpty()) lastRateHeaders = headers
            it.header("x-whisperash-words-left")?.toIntOrNull()?.let { left -> lastWordsLeft = left }
            val text = it.body.string()
            if (!it.isSuccessful) throw errorFrom(provider, it.code, text, t.viaAccount)
            return JSONObject(text)
        }
    }

    companion object {
        /** Reads an OpenAI-style error; account errors keep the server's own (already friendly) words. */
        fun errorFrom(provider: String, status: Int, text: String, viaAccount: Boolean): ApiException {
            val err = try { JSONObject(text).optJSONObject("error") } catch (_: Exception) { null }
            val msg = err?.optString("message")?.takeIf { m -> m.isNotBlank() }
            val code = err?.optString("code")?.takeIf { c -> c.isNotBlank() }
            return if (viaAccount && msg != null) ApiException(status, provider, msg, code)
            else ApiException(status, provider, "$provider $status: ${msg ?: text.take(200)}", code)
        }

        const val BASE = "https://api.groq.com"
        val JSON = "application/json".toMediaType()
    }
}

/** Gemini backup transcription (Interactions API, model gemini-3.5-transcribe), as on the desktop. */
class GeminiClient(private val http: OkHttpClient, private val target: () -> ApiTarget?) {
    fun available() = target() != null

    fun transcribe(audio: File, mime: String, language: String, dictionary: List<String>): SttResult {
        val t = target() ?: throw ApiException(401, "Gemini", "No Gemini key")
        val cfg = JSONObject()
        if (language != "auto") cfg.put("language_codes", JSONArray().put(language))
        if (dictionary.isNotEmpty()) cfg.put("custom_vocabulary", JSONArray(dictionary))
        val body = JSONObject().apply {
            put("model", MODEL)
            put("input", JSONArray().put(JSONObject().apply {
                put("type", "audio")
                put("data", android.util.Base64.encodeToString(audio.readBytes(), android.util.Base64.NO_WRAP))
                // Gemini documents audio/aac where the rest of the pipeline says audio/mp4.
                put("mime_type", if (mime == "audio/mp4") "audio/aac" else mime)
            }))
            if (cfg.length() > 0) put("generation_config", JSONObject().put("transcription_config", cfg))
        }
        val req = Request.Builder().url("${t.base}/v1beta/interactions")
            .apply { if (t.viaAccount) header("Authorization", "Bearer ${t.token}") else header("x-goog-api-key", t.token) }
            .post(body.toString().toRequestBody(GroqClient.JSON)).build()
        val res = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw ApiException(0, "Gemini", "No connection to Gemini")
        }
        res.use {
            val text = it.body.string()
            if (!it.isSuccessful) throw GroqClient.errorFrom(if (t.viaAccount) "WhisperAsh" else "Gemini", it.code, text, t.viaAccount)
            val json = JSONObject(text)
            val status = json.optString("status", "completed")
            if (status != "completed") throw ApiException(500, "Gemini", "Gemini transcription $status")
            var out = json.optString("output_text", "")
            if (out.isBlank()) {
                val parts = ArrayList<String>()
                val steps = json.optJSONArray("steps") ?: JSONArray()
                for (i in 0 until steps.length()) {
                    val content = steps.getJSONObject(i).optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val p = content.getJSONObject(j)
                        if (p.optString("type") == "text") parts += p.optString("text")
                    }
                }
                out = parts.joinToString(" ")
            }
            return SttResult(out.trim(), null, 0.0, MODEL, "gemini")
        }
    }

    companion object {
        const val MODEL = "gemini-3.5-transcribe"
        const val BASE = "https://generativelanguage.googleapis.com"
    }
}
