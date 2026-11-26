package com.whisperash.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP calls of the meeting recorder (contract: meeting-spec.md) and of "Re-transcribe (better model)". Every call
 * takes its [ApiTarget] (own key = provider directly, account = WhisperAsh gateway with `x-whisperash-purpose`:
 * "meeting" for meetings, "dictation" for History re-transcribes). Audio calls may take 10 minutes, text calls 3;
 * explicit re-transcriptions give up after 4.
 */
private fun timed(http: OkHttpClient, minutes: Long) = http.newBuilder()
    .callTimeout(minutes, TimeUnit.MINUTES).readTimeout(minutes, TimeUnit.MINUTES).writeTimeout(minutes, TimeUnit.MINUTES).build()

/** The headers a call sends: the account's session + purpose through the gateway, else the provider's own key header. */
object GatewayHeaders {
    const val MEETING = "meeting"
    const val DICTATION = "dictation"

    fun forTarget(t: ApiTarget, purpose: String, direct: Pair<String, String>): List<Pair<String, String>> =
        if (t.viaAccount) listOf("Authorization" to "Bearer ${t.token}", "x-whisperash-purpose" to purpose) else listOf(direct)
}

private fun Request.Builder.auth(t: ApiTarget, purpose: String, direct: Pair<String, String>): Request.Builder {
    for ((k, v) in GatewayHeaders.forTarget(t, purpose, direct)) header(k, v)
    return this
}

private fun execute(client: OkHttpClient, req: Request, t: ApiTarget, name: String): JSONObject {
    val provider = if (t.viaAccount) "WhisperAsh" else name
    val res = try {
        client.newCall(req).execute()
    } catch (e: java.io.InterruptedIOException) {
        // OkHttp's call / read timeout
        throw ApiException(408, provider, "$name took too long — try again later", "timeout")
    } catch (e: IOException) {
        throw ApiException(0, provider, "No connection to $provider (${e.message ?: e.javaClass.simpleName})")
    }
    res.use {
        val text = it.body.string()
        if (!it.isSuccessful) throw GroqClient.errorFrom(provider, it.code, text, t.viaAccount)
        return try { JSONObject(text) } catch (_: Exception) { throw ApiException(502, provider, "$provider sent an answer that is not JSON") }
    }
}

/** Gemini generateContent: audio part → transcript lines, or a text-only call (romanize, summary). */
class GeminiMeetingClient(http: OkHttpClient) {
    private val audioHttp = timed(http, 10)
    private val textHttp = timed(http, 3)
    private val betterHttp = timed(http, BETTER_TIMEOUT_MIN)

    fun generate(
        t: ApiTarget, system: String?, text: String, audio: File? = null, mime: String = "audio/mp4",
        model: String = MODEL, purpose: String = GatewayHeaders.MEETING, retranscribe: Boolean = false,
    ): String {
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (audio != null) parts.put(JSONObject().put("inline_data", JSONObject()
            .put("mime_type", mime)
            .put("data", java.util.Base64.getEncoder().encodeToString(audio.readBytes()))))
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            if (system != null) put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("generationConfig", JSONObject().put("temperature", 0).put("maxOutputTokens", MAX_OUTPUT).apply {
                if (model == BETTER_MODEL) put("thinkingConfig", JSONObject(BETTER_THINKING))
            })
        }
        val req = Request.Builder().url(url(t, model))
            .auth(t, purpose, "x-goog-api-key" to t.token)
            .post(body.toString().toRequestBody(GroqClient.JSON)).build()
        val client = if (retranscribe) betterHttp else if (audio != null) audioHttp else textHttp
        val json = execute(client, req, t, "Gemini")
        val provider = if (t.viaAccount) "WhisperAsh" else "Gemini"
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw ApiException(502, provider, "Gemini gave no answer" + (json.optJSONObject("promptFeedback")?.optString("blockReason")?.let { " ($it)" } ?: ""))
        val finish = cand.optString("finishReason", "STOP")
        // A cut-off transcript would silently lose the end of the part: let the next engine do it.
        if (finish == "MAX_TOKENS" || finish == "SAFETY" || finish == "RECITATION") throw ApiException(502, provider, "Gemini stopped early ($finish)")
        val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val out = StringBuilder()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            if (p.optBoolean("thought", false)) continue
            out.append(p.optString("text", ""))
        }
        return out.toString().trim()
    }

    companion object {
        // Flash-Lite is used for diary audio and text processing.
        const val MODEL = "gemini-3.5-flash-lite"
        const val MAX_OUTPUT = 16384

        /**
         * "Re-transcribe (better model)" for History and Meetings only. Tested on an 86 s meeting clip: 20 s, better
         * proper nouns than Flash-Lite, with minimal thinking; with thinking left on it hung 5+ minutes.
         */
        const val BETTER_MODEL = "gemini-3.6-flash"
        const val MEETING_MODEL = BETTER_MODEL
        const val BETTER_LABEL = "Gemini 3.6 Flash"
        const val BETTER_TIMEOUT_MIN = 4L
        /** generationConfig.thinkingConfig sent with Gemini 3.6 Flash. */
        val BETTER_THINKING: Map<String, Any> = mapOf("thinkingLevel" to "minimal", "includeThoughts" to false)

        /** Own key: Google directly; account: {server}/gemini/v1beta/models/{model}:generateContent. */
        fun url(t: ApiTarget, model: String) = "${t.base}/v1beta/models/$model:generateContent"
    }
}

/** Deepgram nova-3 batch with speakers (diarize + utterances). */
class DeepgramClient(http: OkHttpClient) {
    private val audioHttp = timed(http, 10)

    fun listen(t: ApiTarget, audio: File, mime: String): List<Utterance> {
        val req = Request.Builder().url("${t.base}/v1/listen?$QUERY")
            .auth(t, GatewayHeaders.MEETING, "Authorization" to "Token ${t.token}")
            .post(audio.asRequestBody(mime.toMediaType())).build()
        val json = execute(audioHttp, req, t, "Deepgram")
        val utt = json.optJSONObject("results")?.optJSONArray("utterances") ?: JSONArray()
        val out = ArrayList<Utterance>()
        for (i in 0 until utt.length()) {
            val u = utt.getJSONObject(i)
            out += Utterance(u.optDouble("start", 0.0), u.optInt("speaker", 0), u.optString("transcript", ""))
        }
        return out
    }

    companion object {
        const val QUERY = "model=nova-3&language=multi&diarize=true&utterances=true&smart_format=true&punctuate=true"
    }
}

/** Groq for meetings: whisper-large-v3 segments (no language, no prompt) and gpt-oss-120b text. */
class GroqMeetingClient(http: OkHttpClient) {
    private val audioHttp = timed(http, 10)
    private val textHttp = timed(http, 3)

    fun transcribe(t: ApiTarget, audio: File, mime: String): List<Segment> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody(mime.toMediaType()))
            .addFormDataPart("model", STT_MODEL)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .build()
        val req = Request.Builder().url("${t.base}/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${t.token}")
            .apply { if (t.viaAccount) header("x-whisperash-purpose", "meeting") }
            .post(body).build()
        val json = execute(audioHttp, req, t, "Groq")
        val segs = json.optJSONArray("segments") ?: JSONArray()
        val out = ArrayList<Segment>()
        for (i in 0 until segs.length()) {
            val s = segs.getJSONObject(i)
            // Whisper's "no speech" segments are where it invents "Thank you." over silence.
            if (s.optDouble("no_speech_prob", 0.0) > 0.8) continue
            out += Segment(s.optDouble("start", 0.0), s.optDouble("end", 0.0), s.optString("text", ""))
        }
        if (out.isEmpty() && segs.length() == 0) {
            val text = json.optString("text", "").trim()
            if (text.isNotEmpty()) out += Segment(0.0, 0.0, text)
        }
        return out
    }

    fun chat(t: ApiTarget, system: String, user: String, model: String = CHAT_MODEL, purpose: String = GatewayHeaders.MEETING): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
            put("temperature", 0)
            put("max_completion_tokens", MAX_TOKENS)
            put("reasoning_effort", "low")
            put("include_reasoning", false)
        }
        val req = Request.Builder().url(chatUrl(t))
            .header("Authorization", "Bearer ${t.token}")
            .apply { if (t.viaAccount) header("x-whisperash-purpose", purpose) }
            .post(body.toString().toRequestBody(GroqClient.JSON)).build()
        val json = execute(textHttp, req, t, "Groq")
        val choice = json.getJSONArray("choices").getJSONObject(0)
        if (choice.optString("finish_reason") == "length") throw ApiException(502, if (t.viaAccount) "WhisperAsh" else "Groq", "Groq answer was cut off")
        return choice.getJSONObject("message").optString("content", "")
    }

    companion object {
        const val STT_MODEL = "whisper-large-v3"
        const val CHAT_MODEL = "openai/gpt-oss-120b"

        /** Own key: Groq directly; account: {server}/groq/openai/v1/chat/completions. */
        fun chatUrl(t: ApiTarget) = "${t.base}/openai/v1/chat/completions"
        const val MAX_TOKENS = 4096
    }
}
