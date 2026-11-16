package com.whisperash.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Accent = Color(0xFFFF8A50)
private val Bg = Color(0xFF101014)
private val CardBg = Color(0xFF1C1C23)
private val Muted = Color(0xFFA0A0AE)

class MainActivity : ComponentActivity() {
    private val app get() = application as WhisperAshApp
    private val tick = mutableIntStateOf(0)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_ASK_MIC, false)) askMic()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg, onPrimary = Color.Black)) {
                AppScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
        app.sync.requestSync(SyncClient.Reason.OPEN)
    }

    fun askMic() = micPermission.launch(Manifest.permission.RECORD_AUDIO)

    fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun keyboardEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    fun keyboardSelected(): Boolean {
        val id = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return id.startsWith("$packageName/")
    }

    @Composable
    private fun AppScreen() {
        var tab by remember { mutableIntStateOf(0) }
        val t = tick.intValue
        val dataVersion by app.sync.dataVersion.collectAsState()
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = CardBg) {
                    listOf("Home" to "⌂", "History" to "☰", "Words" to "Aa", "Settings" to "⚙", "Account" to "☁").forEachIndexed { i, (label, glyph) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Text(glyph, fontSize = 18.sp) }, label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> HomeTab(t + dataVersion) { tab = it }
                    1 -> HistoryTab(dataVersion)
                    2 -> DictionaryTab(dataVersion)
                    3 -> SettingsTab(dataVersion)
                    4 -> AccountTab()
                }
            }
        }
    }

    // ---------- Home ----------
    @Composable
    private fun HomeTab(version: Int, goTo: (Int) -> Unit) {
        val scroll = rememberScrollState()
        var refresh by remember { mutableIntStateOf(0) }
        val usage = remember(version, refresh) { usageSummary(app.db) }
        val timings = remember(version, refresh) { app.db.recentTimings(10) }
        Column(Modifier.verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("WhisperAsh", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text("Voice typing in any app: English, Hindi and Hinglish.", color = Muted)

            Section("Set up") {
                Step("1. Turn on the WhisperAsh keyboard", keyboardEnabled(), "Open") {
                    startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
                }
                Step("2. Allow the microphone", hasMic(), "Allow") { askMic() }
                Step("3. Add your Groq API key", app.secure.has(SecureStore.GROQ_KEY), "Add") { goTo(3) }
                Step("4. Switch to WhisperAsh (🌐 key or here)", keyboardSelected(), "Switch") {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                }
                Step("5. Log in to sync with your PC (optional)", app.sync.signedIn, "Log in") { goTo(4) }
            }

            Section("Try it") {
                var text by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(120.dp),
                    placeholder = { Text("Tap here, then tap the mic on the keyboard") },
                )
            }

            Section("Free words left (Groq free tier)") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Big("%,d".format(usage.leftToday), "left today")
                    Big("%,d".format(usage.leftMonth), "left this month")
                }
                Spacer(Modifier.height(8.dp))
                Small("Dictated: ${"%,d".format(usage.dictatedToday)} words today, ${"%,d".format(usage.dictatedMonth)} this month")
                Small("Speech-to-text, last 24 h: ${usage.sttDay.requests} / ${GroqLimits.STT_RPD} requests, ${usage.sttDay.seconds.toInt()} / ${GroqLimits.AUDIO_PER_DAY} audio s")
                Small("Last hour: ${usage.sttHour.seconds.toInt()} / ${GroqLimits.AUDIO_PER_HOUR} audio s · last minute: ${usage.sttMinute.requests} / ${GroqLimits.STT_RPM} requests")
                val model = app.settings.cleanupModel
                val used = app.db.llmUsage(model, System.currentTimeMillis() - 86_400_000L)
                val perCleanup = if (used.requests >= 3) used.tokens / used.requests else 1_100L
                val cleanupsLeft = minOf((GroqLimits.LLM_TPD - used.tokens).coerceAtLeast(0) / perCleanup, (GroqLimits.LLM_RPD - used.requests).coerceAtLeast(0).toLong())
                Small("Cleanups left today with ${model.substringAfter('/')}: about $cleanupsLeft (${"%,d".format(used.tokens)} / ${"%,d".format(GroqLimits.LLM_TPD)} tokens used, ~$perCleanup per cleanup). After that, ${Settings.FALLBACK_CLEANUP_MODEL.substringAfter('/')} takes over.")
                Small("Cleanup, last 24 h: ${usage.llmDay.requests} / ${GroqLimits.LLM_RPD} requests, ${"%,d".format(usage.llmDay.tokens)} / ${"%,d".format(GroqLimits.LLM_TPD)} tokens")
                Small("Estimate: ${usage.perSecond} words per billed second; Groq bills at least ${GroqLimits.MIN_BILLED_SECONDS} s per clip. Counts this phone only.")
                val rate = app.groq.lastRateHeaders
                if (rate.isNotEmpty()) {
                    val rem = rate["x-ratelimit-remaining-requests"]
                    val lim = rate["x-ratelimit-limit-requests"]
                    if (rem != null && lim != null) Small("Groq says (last request's model): $rem / $lim requests left today")
                }
            }

            Section("Speed") {
                if (timings.isEmpty()) Small("No dictations yet.")
                else {
                    val last = timings.first()
                    Big("%.1f s".format(last.optLong("stop_to_insert_ms") / 1000.0), "stop → text, last dictation")
                    Small("Upload + Whisper ${last.optLong("stt_ms")} ms · cleanup ${last.optLong("cleanup_ms")} ms · audio ${last.optLong("audio_ms") / 1000.0} s · ${last.optLong("upload_bytes") / 1024} KB")
                    val avg = timings.map { it.optLong("stop_to_insert_ms") }.average()
                    Small("Average of last ${timings.size}: %.1f s".format(avg / 1000.0))
                }
            }
        }
    }

    // ---------- History ----------
    @Composable
    private fun HistoryTab(version: Int) {
        var query by remember { mutableStateOf("") }
        var refresh by remember { mutableIntStateOf(0) }
        val items = remember(query, version, refresh) { app.db.history(query) }
        val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search ${app.db.historyCount()} dictations") }, singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(items, key = { it.id }) { h ->
                    var expanded by remember { mutableStateOf(false) }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(h.text, maxLines = if (expanded) 50 else 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Small(df.format(Date(h.createdMs)) + (h.app?.let { " · " + it.substringAfterLast('.') } ?: "") +
                                (h.model?.let { " · $it" } ?: ""))
                            if (expanded) {
                                if (!h.rawText.isNullOrBlank() && h.rawText != h.text) Small("Heard: ${h.rawText}")
                                Row {
                                    TextButton(onClick = { copy(h.text) }) { Text("Copy") }
                                    TextButton(onClick = {
                                        app.db.deleteHistory(h.id); refresh++
                                        app.sync.requestSync(SyncClient.Reason.CHANGE)
                                    }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- Dictionary ----------
    @Composable
    private fun DictionaryTab(version: Int) {
        var refresh by remember { mutableIntStateOf(0) }
        val words = remember(version, refresh) { app.db.dictionary() }
        var input by remember { mutableStateOf("") }
        Column(Modifier.padding(16.dp)) {
            Text("Personal dictionary", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Small("Names and terms WhisperAsh should spell your way. Synced with your PC.")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("Add a word") })
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    input.split(',').forEach { app.db.addWord(it) }
                    input = ""; refresh++
                    app.sync.requestSync(SyncClient.Reason.CHANGE)
                }) { Text("Add") }
            }
            Spacer(Modifier.height(8.dp))
            Small("${words.size} words")
            LazyColumn {
                items(words, key = { it }) { w ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(w, Modifier.weight(1f))
                        TextButton(onClick = { app.db.removeWord(w); refresh++; app.sync.requestSync(SyncClient.Reason.CHANGE) }) { Text("Remove") }
                    }
                    HorizontalDivider(color = CardBg)
                }
            }
        }
    }

    // ---------- Settings ----------
    @Composable
    private fun SettingsTab(version: Int) {
        val s = app.settings
        var r by remember { mutableIntStateOf(0) }
        val v = r + version
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("API keys (stored encrypted on this phone)") {
                KeyField("Groq API key", SecureStore.GROQ_KEY) { r++ }
                Small("Free key: console.groq.com → API Keys. Used for Whisper and cleanup.")
                Spacer(Modifier.height(8.dp))
                KeyField("Gemini API key (optional backup)", SecureStore.GEMINI_KEY) { r++ }
                Toggle("Use Gemini when Groq fails", s.geminiBackup) { s.geminiBackup = it; r++ }
            }
            Section("Language") {
                Choice(Settings.LANGUAGES, s.language.takeIf { l -> Settings.LANGUAGES.any { it.first == l } } ?: "auto", v) {
                    s.setSynced("preferredLanguage", it); r++
                }
                Small("Auto-detect handles English, Hindi and Hinglish.")
                Spacer(Modifier.height(6.dp))
                Text("Hindi and Hinglish are written as", fontWeight = FontWeight.Medium)
                Choice(
                    listOf("roman" to "Hinglish (Roman)", "devanagari" to "हिन्दी (Devanagari)", "english" to "English (translate)"),
                    s.hindiScript, v,
                ) { s.setSynced("hindiScriptPreference", it); r++ }
            }
            Section("Cleanup") {
                Toggle("Clean up text (fillers, punctuation, corrections)", s.useCleanup) {
                    s.setSynced("useCleanupModel", if (it) "true" else "false"); r++
                }
                Text("Mode", fontWeight = FontWeight.Medium)
                Choice(
                    listOf(Settings.FASTER_MODEL to "Faster (default)", Settings.PRECISE_MODEL to "Precise"),
                    if (s.cleanupModel == Settings.PRECISE_MODEL) Settings.PRECISE_MODEL else Settings.FASTER_MODEL, v,
                ) { s.setSynced("cleanupModel", it); r++ }
                Small(
                    if (s.cleanupModel == Settings.FASTER_MODEL) "Faster: Qwen 3.8 27B. Keeps your wording, about 0.3–0.5 s quicker."
                    else if (s.cleanupModel == Settings.PRECISE_MODEL) "Precise: GPT-OSS 120B. Stronger on Hindi and Hinglish, about 0.3–0.5 s slower; can shorten long English dictations."
                    else "Using ${s.cleanupModel} (set elsewhere). Pick a mode to change it."
                )
                Small("Each mode has its own free Groq allowance. If one fails, GPT-OSS 20B is tried, then the raw transcript is used. Synced with your PC.")
                Text("Speech model", fontWeight = FontWeight.Medium)
                Choice(listOf("whisper-large-v3" to "Whisper large v3", "whisper-large-v3-turbo" to "Turbo (faster)"), s.sttModel, v) {
                    s.setSynced("cloudTranscriptionModel", it); r++
                }
            }
            Section("Tone & style per app") {
                Toggle("Match the tone to the app", s.toneEnabled) { s.setSynced("toneStyleEnabled", if (it) "true" else "false"); r++ }
                if (s.toneEnabled) {
                    val tones = s.toneStyles
                    for ((cat, options) in Prompts.TONE_OPTIONS) {
                        Text(Prompts.CATEGORY_LABELS[cat] ?: cat, fontWeight = FontWeight.Medium)
                        Choice(options.map { it to (Prompts.TONE_LABELS[it] ?: it) }, tones[cat] ?: "formal", v) { s.setTone(cat, it); r++ }
                    }
                }
            }
            Section("Keyboard") {
                Toggle("Vibrate on key press", s.haptics) { s.haptics = it; r++ }
                Toggle("Add a space before dictated text", s.autoSpace) { s.autoSpace = it; r++ }
                Toggle("Open the keyboard on the letter keys", s.typingMode) { s.typingMode = it; r++ }
                Small("Mic key: tap to start and tap to finish, or hold while you talk and let go. ABC switches to letter keys for typing, and 🎤 there goes back to the mic and starts listening. 🌐 switches to your other keyboard; hold it to pick one.")
            }
            Small("WhisperAsh ${BuildConfig.VERSION_NAME}")
        }
    }

    // ---------- Account ----------
    @Composable
    private fun AccountTab() {
        val state by app.sync.state.collectAsState()
        val scope = rememberCoroutineScope()
        var email by remember { mutableStateOf(app.settings.email) }
        var password by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var server by remember { mutableStateOf(app.settings.serverUrl) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var registerMode by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { app.sync.refreshState() }

        fun run(block: suspend () -> Unit) {
            busy = true; message = ""
            scope.launch {
                try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { message = friendly(e.message) }
                busy = false
                app.sync.refreshState()
            }
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Account & sync", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (state.signedIn) {
                Section("Signed in") {
                    Text(state.email, fontWeight = FontWeight.Medium)
                    Small(if (state.lastSyncAt > 0) "Last sync: " + DateFormat.getDateTimeInstance().format(Date(state.lastSyncAt)) else "Not synced yet")
                    Small("History: ${app.db.historyCount()} · dictionary: ${app.db.dictionary().size} words · waiting to upload: ${state.pending}")
                    if (state.lastError.isNotEmpty()) Text(state.lastError, color = Color(0xFFFF8080), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !state.busy, onClick = { run { app.sync.syncNow(SyncClient.Reason.MANUAL) } }) {
                            Text(if (state.busy) "Syncing…" else "Sync now")
                        }
                        OutlinedButton(onClick = { run { app.sync.logout() } }) { Text("Log out") }
                    }
                }
                Small("History, dictionary and settings (language, Hindi script, cleanup, tone) sync with the WhisperAsh desktop app. API keys never leave this phone.")
            } else {
                Section(if (registerMode) "Create an account" else "Log in with your WhisperAsh account") {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (registerMode) OutlinedTextField(code, { code = it }, label = { Text("Sign-up code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !busy && email.isNotBlank() && password.length >= 8, onClick = {
                            app.settings.serverUrl = server
                            val e = email; val p = password; val c = code
                            run { if (registerMode) app.sync.register(e, p, c) else app.sync.login(e, p) }
                            password = ""
                        }) { Text(if (busy) "Please wait…" else if (registerMode) "Create account" else "Log in") }
                        TextButton(onClick = { registerMode = !registerMode }) { Text(if (registerMode) "I have an account" else "New account") }
                    }
                    if (state.lastError.isNotEmpty() && message.isEmpty()) Small(state.lastError)
                }
                Section("Server") {
                    OutlinedTextField(server, { server = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Sync server URL") })
                }
            }
            if (message.isNotEmpty()) Text(message, color = Color(0xFFFF8080))
        }
    }

    private fun friendly(code: String?) = when (code) {
        "invalid_credentials" -> "Wrong email or password."
        "too_many_attempts" -> "Too many tries. Wait 15 minutes."
        "email_taken" -> "That email already has an account. Log in instead."
        "invalid_signup_code" -> "Wrong sign-up code."
        "password_too_short" -> "Password must be at least 8 characters."
        "invalid_email" -> "That email doesn't look right."
        null -> "Something went wrong."
        else -> code
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("WhisperAsh", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    // ---------- small pieces ----------
    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Accent)
                content()
            }
        }
    }

    @Composable
    private fun Step(label: String, done: Boolean, action: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✓" else "○", color = if (done) Color(0xFF6EE7A0) else Muted, fontSize = 18.sp, modifier = Modifier.width(26.dp))
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            if (!done) TextButton(onClick = onClick) { Text(action) }
        }
    }

    @Composable
    private fun Big(value: String, label: String) {
        Column {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 12.sp)
        }
    }

    @Composable
    private fun Small(text: String) = Text(text, color = Muted, fontSize = 12.sp)

    @Composable
    private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            Switch(checked = value, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun Choice(options: List<Pair<String, String>>, selected: String, @Suppress("UNUSED_PARAMETER") version: Int, onPick: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.chunked(3).forEach { rowOpts ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowOpts.forEach { (value, label) ->
                        FilterChip(selected = selected == value, onClick = { onPick(value) }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
            }
        }
    }

    @Composable
    private fun KeyField(label: String, name: String, onSaved: () -> Unit) {
        var has by remember { mutableStateOf(app.secure.has(name)) }
        var value by remember { mutableStateOf("") }
        if (has) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$label: saved ✓", Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = { app.secure.put(name, null); has = false; onSaved() }) { Text("Remove") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value, { value = it.trim() }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = value.length >= 20, onClick = {
                    app.secure.put(name, value); value = ""; has = true; onSaved()
                }) { Text("Save") }
            }
        }
    }

    companion object {
        const val EXTRA_ASK_MIC = "ask_mic"
    }
}
