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

class ApiException(val status: Int, val provider: String, message: String) : IOException(message) {
    /** Worth trying the backup provider: rate limit, server trouble, or no answer. */
    val retryable get() = status == 0 || status == 408 || status == 429 || status >= 500
}

data class SttResult(val text: String, val language: String?, val noSpeechProb: Double, val model: String, val provider: String)
data class CleanupResult(val text: String, val model: String, val tokens: Int)

class GroqClient(private val http: OkHttpClient, private val key: () -> String?) {
    // Cleanup must never hold the text back for long: past 15 s the raw transcript is used.
    private val chatHttp = http.newBuilder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    private val sttHttp = http.newBuilder().callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    /** Last x-ratelimit-* headers Groq sent, for the usage screen. */
    @Volatile var lastRateHeaders: Map<String, String> = emptyMap()
        private set

    /** Opens the TLS connection while the user is still talking (no key sent). */
    fun warmUp() {
        try {
            http.newCall(Request.Builder().url("$BASE/").head().build()).execute().close()
        } catch (_: Exception) {
        }
    }

    fun transcribe(audio: File, mime: String, model: String, language: String, prompt: String): SttResult {
        val apiKey = key() ?: throw ApiException(401, "Groq", "Add your Groq API key in WhisperAsh first")
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
        val req = Request.Builder().url("$BASE/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey").post(body).build()
        val json = call(sttHttp, req)
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
        val apiKey = key() ?: throw ApiException(401, "Groq", "Add your Groq API key in WhisperAsh first")
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
        val req = Request.Builder().url("$BASE/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON)).build()
        val json = call(chatHttp, req)
        val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val tokens = json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
        return CleanupResult(msg.optString("content", ""), model, tokens)
    }

    private fun call(client: OkHttpClient, req: Request): JSONObject {
        val res: Response = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw ApiException(0, "Groq", "No connection to Groq (${e.message ?: e.javaClass.simpleName})")
        }
        res.use {
            val headers = HashMap<String, String>()
            for ((name, value) in it.headers) if (name.lowercase().startsWith("x-ratelimit")) headers[name.lowercase()] = value
            if (headers.isNotEmpty()) lastRateHeaders = headers
            val text = it.body.string()
            if (!it.isSuccessful) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                } catch (_: Exception) {
                    null
                }
                throw ApiException(it.code, "Groq", "Groq ${it.code}: ${msg ?: text.take(200)}")
            }
            return JSONObject(text)
        }
    }

    companion object {
        const val BASE = "https://api.groq.com"
        val JSON = "application/json".toMediaType()
    }
}

/** Gemini backup transcription (Interactions API, model gemini-3.5-transcribe), as on the desktop. */
class GeminiClient(private val http: OkHttpClient, private val key: () -> String?) {
    fun available() = !key().isNullOrBlank()

    fun transcribe(audio: File, mime: String, language: String, dictionary: List<String>): SttResult {
        val apiKey = key() ?: throw ApiException(401, "Gemini", "No Gemini key")
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
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/interactions")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(GroqClient.JSON)).build()
        val res = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw ApiException(0, "Gemini", "No connection to Gemini")
        }
        res.use {
            val text = it.body.string()
            if (!it.isSuccessful) throw ApiException(it.code, "Gemini", "Gemini ${it.code}: ${text.take(200)}")
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
    }
}
