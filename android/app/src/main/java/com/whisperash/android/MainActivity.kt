package com.whisperash.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.whisperash.android.ui.AiLabel
import com.whisperash.android.ui.BackBar
import com.whisperash.android.ui.Btn
import com.whisperash.android.ui.BtnKind
import com.whisperash.android.ui.Chip
import com.whisperash.android.ui.Eyebrow
import com.whisperash.android.ui.G
import com.whisperash.android.ui.GlassCard
import com.whisperash.android.ui.GlassField
import com.whisperash.android.ui.GlassList
import com.whisperash.android.ui.GlassTabBar
import com.whisperash.android.ui.GlassTheme
import com.whisperash.android.ui.GlassWorld
import com.whisperash.android.ui.Gutter
import com.whisperash.android.ui.Ic
import com.whisperash.android.ui.IconSquare
import com.whisperash.android.ui.LargeTitle
import com.whisperash.android.ui.ListRow
import com.whisperash.android.ui.Meta
import com.whisperash.android.ui.NoteBox
import com.whisperash.android.ui.RoundGlass
import com.whisperash.android.ui.SearchBox
import com.whisperash.android.ui.SectionTitle
import com.whisperash.android.ui.Segmented
import com.whisperash.android.ui.Subtitle
import com.whisperash.android.ui.TabBarClearance
import com.whisperash.android.ui.TabItem
import com.whisperash.android.ui.ToggleRow
import com.whisperash.android.ui.Tone
import com.whisperash.android.ui.reducedMotion
import com.whisperash.android.ui.AudioChip
import com.whisperash.android.ui.GlassSheet
import com.whisperash.android.ui.SheetRow
import com.whisperash.android.ui.Spinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.whisperash.android.ui.glass
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Bottom space a scrolling screen leaves for the floating tab bar and the system navigation bar. */
@Composable
fun bottomClearance() = TabBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

class MainActivity : ComponentActivity() {
    private val app get() = application as WhisperAshApp
    private val tick = mutableIntStateOf(0)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick.intValue++ }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    /** Tab asked for by an intent (the meeting notification opens Meetings); -1 = none. */
    private val requestedTab = mutableIntStateOf(-1)
    /** "speak" / "type" from the home-screen widget: open Notes and start that. */
    private val requestedNote = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_ASK_MIC, false)) askMic()
        requestedTab.intValue = intent.getIntExtra(EXTRA_TAB, -1)
        requestedNote.value = intent.getStringExtra(EXTRA_NOTE)
        setContent { GlassTheme { GlassWorld { AppScreen() } } }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
        app.sync.requestSync(SyncClient.Reason.OPEN)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra(EXTRA_TAB, -1).takeIf { it >= 0 }?.let { requestedTab.intValue = it }
        intent.getStringExtra(EXTRA_NOTE)?.let { requestedNote.value = it }
    }

    fun askMic() = micPermission.launch(Manifest.permission.RECORD_AUDIO)

    // ---------- note lock: the phone's own fingerprint / screen lock (framework only, no library) ----------
    private var pendingUnlock: (() -> Unit)? = null
    private val credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ok = pendingUnlock
        pendingUnlock = null
        if (r.resultCode == RESULT_OK) ok?.invoke()
    }

    /** Locking needs a secure screen lock (PIN, pattern, password or fingerprint). */
    fun canLock(): Boolean = (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure

    fun unlock(reason: String, onOk: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!km.isDeviceSecure) { onOk(); return }   // the lock was set while a screen lock existed; nothing to check now
        val callback = object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { onOk() }
        }
        val signal = android.os.CancellationSignal()
        when {
            android.os.Build.VERSION.SDK_INT >= 30 -> android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("Unlock note").setSubtitle(reason)
                .setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build().authenticate(signal, mainExecutor, callback)
            android.os.Build.VERSION.SDK_INT >= 29 -> @Suppress("DEPRECATION") android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("Unlock note").setSubtitle(reason).setDeviceCredentialAllowed(true)
                .build().authenticate(signal, mainExecutor, callback)
            else -> {
                @Suppress("DEPRECATION")
                val i = km.createConfirmDeviceCredentialIntent("Unlock note", reason) ?: run { onOk(); return }
                pendingUnlock = onOk
                credentialLauncher.launch(i)
            }
        }
    }

    fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Android 13+: the recording notification needs this; asked once, recording works either way. */
    fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33 || app.settings.askedNotifications) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        app.settings.askedNotifications = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun keyboardEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    fun keyboardSelected(): Boolean {
        val id = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return id.startsWith("$packageName/")
    }

    private fun showKeyboardPicker() =
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()

    // ---------- setup ----------
    private data class SetupStep(val title: String, val done: Boolean, val action: String, val run: () -> Unit)

    private fun setupSteps(openSettings: (String?) -> Unit): List<SetupStep> {
        val kbOn = keyboardEnabled()
        val kbPicked = keyboardSelected()
        return listOf(
            SetupStep(
                if (kbOn) "Switch to the WhisperAsh keyboard" else "Turn on the WhisperAsh keyboard", kbOn && kbPicked,
                if (kbOn) "Switch" else "Turn on",
            ) { if (kbOn) showKeyboardPicker() else startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) },
            SetupStep("Allow the microphone", hasMic(), "Allow") { askMic() },
            if (app.useAccount()) SetupStep("Sign in to WhisperAsh", app.canDictate(), "Sign in") { openSettings(SUB_ACCOUNT) }
            else SetupStep("Add your Groq key", app.canDictate(), "Add") { openSettings(null) },
        )
    }

    // ---------- app shell ----------
    @Composable
    private fun AppScreen() {
        var tab by remember { mutableIntStateOf(MeetingRecorderService.state.value.let {
            when {
                it.recordingId == null -> TAB_HOME
                it.recordingKind.isDiary -> TAB_DIARIES
                else -> TAB_MEETINGS
            }
        }) }
        var settingsSub by remember { mutableStateOf<String?>(null) }
        var notesStart by remember { mutableStateOf<String?>(null) }
        var meetingStart by remember { mutableStateOf(false) }
        val asked = requestedTab.intValue
        LaunchedEffect(asked) { if (asked >= 0) { tab = asked; requestedTab.intValue = -1 } }
        val askedNote = requestedNote.value
        LaunchedEffect(askedNote) { if (askedNote != null) { notesStart = askedNote; tab = TAB_NOTES; requestedNote.value = null } }
        val t = tick.intValue
        val dataVersion by app.sync.dataVersion.collectAsState()
        val still = reducedMotion()

        fun openSettings(sub: String?) { settingsSub = sub; tab = TAB_SETTINGS }
        BackHandler(enabled = tab == TAB_SETTINGS && settingsSub != null) { settingsSub = null }
        BackHandler(enabled = tab != TAB_HOME && !(tab == TAB_SETTINGS && settingsSub != null)) { tab = TAB_HOME }

        val overlay = remember { com.whisperash.android.ui.OverlayHost() }
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        androidx.compose.runtime.CompositionLocalProvider(com.whisperash.android.ui.LocalOverlay provides overlay) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = tab, modifier = Modifier.fillMaxSize().statusBarsPadding(), label = "tab",
                transitionSpec = { if (still) fadeIn(tween(0)) togetherWith fadeOut(tween(0)) else fadeIn(tween(220, easing = G.Ease)) togetherWith fadeOut(tween(160, easing = G.Ease)) },
            ) { current ->
                when (current) {
                    TAB_HOME -> HomeTab(t + dataVersion,
                        onNotes = { kind -> notesStart = kind; tab = TAB_NOTES },
                        onMeeting = { record -> meetingStart = record; tab = TAB_MEETINGS },
                        openSettings = { openSettings(it) })
                    TAB_NOTES -> NotesTab(app, dataVersion, hasMic = { hasMic() }, askMic = { askMic() },
                        start = notesStart, onStartHandled = { notesStart = null },
                        unlock = { reason, ok -> unlock(reason, ok) }, canLock = { canLock() })
                    TAB_MEETINGS -> MeetingsTab(app, dataVersion, hasMic = { hasMic() }, askMic = { askMic() },
                        askNotificationsOnce = { askNotificationsOnce() },
                        autoRecord = meetingStart, onAutoRecordHandled = { meetingStart = false },
                        onOpenOtherRecorder = { tab = TAB_DIARIES })
                    TAB_DIARIES -> MeetingsTab(app, dataVersion, hasMic = { hasMic() }, askMic = { askMic() },
                        askNotificationsOnce = { askNotificationsOnce() }, kind = RecordingKind.DIARY,
                        onOpenOtherRecorder = { tab = TAB_MEETINGS })
                    else -> SettingsTab(dataVersion + t, settingsSub, still,
                        open = { settingsSub = it },
                        reopenSetup = { app.settings.setupDismissed = false; tab = TAB_HOME })
                }
            }
            com.whisperash.android.ui.BottomFade(Modifier.align(Alignment.BottomCenter), navBar)
            GlassTabBar(
                listOf(TabItem("Home", Ic.home), TabItem("Notes", Ic.note), TabItem("Meetings", Ic.users),
                    TabItem("Diaries", Ic.pen), TabItem("Settings", Ic.gear)),
                tab, { i -> if (i == TAB_SETTINGS && tab == TAB_SETTINGS) settingsSub = null; tab = i },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
            // Sheets from any screen, drawn above the tab bar.
            overlay.content?.invoke()
        }
        }
    }

    // ---------- Home ----------
    @Composable
    private fun HomeTab(version: Int, onNotes: (String) -> Unit, onMeeting: (Boolean) -> Unit, openSettings: (String?) -> Unit) {
        var dismissed by remember { mutableStateOf(app.settings.setupDismissed) }
        val steps = setupSteps(openSettings)
        val doneCount = steps.count { it.done }
        val recent = remember(version) { app.db.history(limit = 3) }
        val noteCount = remember(version) { app.db.notes().size }
        var plan by remember { mutableStateOf(app.sync.lastPlan) }
        LaunchedEffect(version, app.sync.signedIn) {
            if (app.sync.signedIn) plan = withContext(Dispatchers.IO) { app.sync.fetchPlan() } ?: plan
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter)
                .padding(top = 12.dp, bottom = bottomClearance()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle("WhisperAsh")
            Subtitle(usageLine(plan, version))

            if (doneCount < steps.size && !dismissed) GlassCard(fill = G.TintPeach) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Finish setting up", style = G.SectionTitle.copy(fontSize = 19.sp))
                        Meta("$doneCount of ${steps.size} done")
                    }
                    RoundGlass(Ic.close, "Hide setup", { app.settings.setupDismissed = true; dismissed = true })
                }
                Spacer(Modifier.height(6.dp))
                steps.forEachIndexed { i, s ->
                    ListRow(s.title, divider = i < steps.size - 1,
                        trailing = {
                            if (s.done) Chip("Done", Tone.Ok, icon = Ic.check)
                            else Btn(s.action, s.run, kind = BtnKind.Glass)
                        })
                }
            }

            // Take notes: the main thing here, so the one solid rust button.
            GlassCard(fill = G.TintPeach, onClick = { onNotes("list") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(Ic.note, Tone.Accent, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Take notes", style = G.ItemTitle.copy(fontSize = 19.sp))
                        Meta(if (noteCount == 0) "Say it; it's written down the way you said it" else counted(noteCount, "note") + " · lists and to-dos kept as you said them")
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Speak a note", { onNotes("speak") }, Modifier.weight(1f), BtnKind.Primary, Ic.mic)
                    Btn("Type", { onNotes("type") }, kind = BtnKind.Glass, icon = Ic.pen)
                }
            }

            GlassCard(fill = G.TintBlue, onClick = { onMeeting(false) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(Ic.users, Tone.Info, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Meetings", style = G.ItemTitle.copy(fontSize = 19.sp))
                        Meta("Record a meeting: transcript with speakers, plus a summary")
                    }
                }
                Spacer(Modifier.height(14.dp))
                Btn("Record meeting", { onMeeting(true) }, kind = BtnKind.Tint, icon = Ic.wave)
                plan?.meetingShort()?.takeIf { app.sync.signedIn }?.let { Meta(it, Modifier.padding(start = 6.dp, top = 8.dp)) }
            }

            GlassCard(fill = G.TintLilac) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(Ic.history, Tone.Lilac, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("History", Modifier.weight(1f), style = G.ItemTitle.copy(fontSize = 19.sp))
                    Text("See all", Modifier.clip(G.Pill).clickable { openSettings(SUB_HISTORY) }.padding(10.dp),
                        style = G.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = G.Accent))
                }
                if (recent.isEmpty()) Meta("Your dictations from the keyboard show up here.", Modifier.padding(top = 8.dp))
                recent.forEachIndexed { i, h ->
                    ListRow(h.text.replace('\n', ' '), relTime(h.createdMs), divider = i < recent.size - 1,
                        onClick = { openSettings(SUB_HISTORY) }, trailing = {})
                }
            }

            GlassCard(padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(Ic.keyboard, Tone.Neutral)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dictate anywhere with the WhisperAsh keyboard", style = G.ItemTitle.copy(fontSize = 15.sp))
                        Meta("Tap the mic on the keyboard in any app: English, Hindi or Hinglish.")
                    }
                }
                Spacer(Modifier.height(10.dp))
                var tryText by remember { mutableStateOf("") }
                GlassField(tryText, { tryText = it }, placeholder = "Tap here, then the mic on the keyboard", singleLine = false, minHeight = 56.dp)
                if (keyboardEnabled() && !keyboardSelected()) {
                    Spacer(Modifier.height(8.dp))
                    Btn("Switch keyboard", { showKeyboardPicker() }, kind = BtnKind.Glass, icon = Ic.keyboard)
                }
            }
        }
    }

    private fun usageLine(plan: AccountPlan?, @Suppress("UNUSED_PARAMETER") version: Int): String = when {
        app.useAccount() && !app.sync.signedIn -> "Sign in to start dictating: free words every day."
        app.useAccount() && plan == null -> "Checking your allowance…"
        app.useAccount() -> listOfNotNull(plan!!.summary(), plan.meetingSummary()?.takeIf { plan.status != "blocked" }).joinToString(" · ")
        else -> "Your own keys · about " + counted(usageSummary(app.db).leftToday, "word") + " left today on Groq's free tier"
    }

    private fun relTime(ms: Long): String {
        val d = System.currentTimeMillis() - ms
        return when {
            d < 60_000 -> "Just now"
            d < 3_600_000 -> "${d / 60_000} min ago"
            d < 86_400_000 -> "${d / 3_600_000} h ago"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
        }
    }

    // ---------- Settings ----------
    @Composable
    private fun SettingsTab(version: Int, sub: String?, still: Boolean, open: (String?) -> Unit, reopenSetup: () -> Unit) {
        AnimatedContent(
            sub, label = "settings",
            transitionSpec = {
                if (still) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else if (targetState != null) slideInHorizontally(tween(300, easing = G.Ease)) { it } togetherWith fadeOut(tween(200))
                else fadeIn(tween(200)) togetherWith slideOutHorizontally(tween(300, easing = G.Ease)) { it }
            },
        ) { s ->
            when (s) {
                SUB_ACCOUNT -> SubScreen("Account", { open(null) }) { AccountScreen() }
                SUB_WORDS -> WordsScreen(version) { open(null) }
                SUB_HISTORY -> HistoryScreen(version) { open(null) }
                else -> SettingsMain(version, open, reopenSetup)
            }
        }
    }

    @Composable
    private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = bottomClearance()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackBar("Settings", onBack)
            LargeTitle(title)
            content()
        }
    }

    @Composable
    private fun SettingsMain(version: Int, open: (String?) -> Unit, reopenSetup: () -> Unit) {
        val s = app.settings
        var r by remember { mutableIntStateOf(0) }
        val v = r + version
        val state by app.sync.state.collectAsState()
        val wordCount = remember(v) { app.db.dictionary().size }
        val historyCount = remember(v) { app.db.historyCount() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter)
                .padding(top = 12.dp, bottom = bottomClearance()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LargeTitle("Settings")
            GlassList {
                ListRow("Account", if (state.signedIn) state.email else "Sign in to sync and get free words", Ic.user, Tone.Accent, onClick = { open(SUB_ACCOUNT) })
                ListRow("Words", counted(wordCount, "word") + " spelled your way", Ic.type, Tone.Lilac, onClick = { open(SUB_WORDS) })
                val incomplete = setupSteps { open(it) }.any { !it.done }
                ListRow("History", counted(historyCount, "dictation"), Ic.history, Tone.Info, divider = s.setupDismissed && incomplete, onClick = { open(SUB_HISTORY) })
                if (s.setupDismissed && incomplete) ListRow("Setup checklist", "A few steps are not done yet", Ic.listChecks, Tone.Wait, divider = false, onClick = reopenSetup)
            }

            SectionTitle("Speech engine")
            GlassCard {
                val account = app.useAccount()
                Segmented(listOf(Settings.ENGINE_ACCOUNT to "WhisperAsh account", Settings.ENGINE_OWN to "My own keys"),
                    if (account) Settings.ENGINE_ACCOUNT else Settings.ENGINE_OWN, { s.engineSource = it; r++ })
                Spacer(Modifier.height(10.dp))
                if (account) {
                    Meta("Recommended. Sign in under Account: WhisperAsh's keys are used, with a daily word allowance shown on Home. Nothing to set up.")
                } else {
                    Meta("Your keys are stored encrypted on this phone and never leave it. No WhisperAsh word limit; the providers' own free limits apply.")
                    Spacer(Modifier.height(10.dp))
                    KeyField("Groq API key", SecureStore.GROQ_KEY, "Free key: console.groq.com → API Keys. Used for Whisper and cleanup.") { r++ }
                    KeyField("Gemini API key (optional backup)", SecureStore.GEMINI_KEY, null) { r++ }
                    KeyField("Deepgram key", SecureStore.DEEPGRAM_KEY, "Optional, for meetings: Gemini transcribes them first, then Deepgram, then Groq. A key saved here is used for meetings even when you dictate with the WhisperAsh account.") { r++ }
                    val u = remember(v) { usageSummary(app.db) }
                    Meta("Groq free tier left today: about %,d words · %,d this month (this phone only).".format(u.leftToday, u.leftMonth), Modifier.padding(top = 4.dp))
                }
                ToggleRow("Use Gemini when Groq fails", s.geminiBackup, divider = false) { s.geminiBackup = it; r++ }
            }

            SectionTitle("Language")
            GlassCard {
                Segmented(Settings.LANGUAGES, s.language.takeIf { l -> Settings.LANGUAGES.any { it.first == l } } ?: "auto", { s.setSynced("preferredLanguage", it); r++ })
                Meta("Auto-detect handles English, Hindi and Hinglish.", Modifier.padding(top = 8.dp, bottom = 12.dp))
                Eyebrow("Hindi and Hinglish are written as")
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("roman" to "Hinglish", "devanagari" to "हिन्दी", "english" to "English"), s.hindiScript, { s.setSynced("hindiScriptPreference", it); r++ })
            }

            SectionTitle("Cleanup")
            GlassCard {
                ToggleRow("Clean up text", s.useCleanup, "Fillers, punctuation and corrections") { s.setSynced("useCleanupModel", if (it) "true" else "false"); r++ }
                Spacer(Modifier.height(10.dp))
                Eyebrow("Mode")
                Spacer(Modifier.height(6.dp))
                Segmented(listOf(Settings.FASTER_MODEL to "Faster", Settings.PRECISE_MODEL to "Precise"),
                    if (s.cleanupModel == Settings.PRECISE_MODEL) Settings.PRECISE_MODEL else Settings.FASTER_MODEL, { s.setSynced("cleanupModel", it); r++ })
                Meta(
                    when (s.cleanupModel) {
                        Settings.FASTER_MODEL -> "Faster: Qwen 3.8 27B. Keeps your wording, about 0.3–0.5 s quicker."
                        Settings.PRECISE_MODEL -> "Precise: GPT-OSS 120B. Stronger on Hindi and Hinglish, about 0.3–0.5 s slower; can shorten long English dictations."
                        else -> "Using ${s.cleanupModel} (set elsewhere). Pick a mode to change it."
                    } + " If one fails, GPT-OSS 20B is tried, then the raw transcript is used. Synced with your PC.",
                    Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                Eyebrow("Speech model")
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("whisper-large-v3" to "Whisper large v3", "whisper-large-v3-turbo" to "Turbo"), s.sttModel, { s.setSynced("cloudTranscriptionModel", it); r++ })
            }

            SectionTitle("Tone & style per app")
            GlassCard {
                ToggleRow("Match the tone to the app", s.toneEnabled, divider = s.toneEnabled) { s.setSynced("toneStyleEnabled", if (it) "true" else "false"); r++ }
                if (s.toneEnabled) {
                    val tones = s.toneStyles
                    for ((cat, options) in Prompts.TONE_OPTIONS) {
                        Spacer(Modifier.height(10.dp))
                        Eyebrow(Prompts.CATEGORY_LABELS[cat] ?: cat)
                        Spacer(Modifier.height(6.dp))
                        Segmented(options.map { it to (Prompts.TONE_LABELS[it] ?: it) }, tones[cat] ?: "formal", { s.setTone(cat, it); r++ })
                    }
                }
            }

            SectionTitle("Keyboard")
            GlassCard(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                ToggleRow("Vibrate on key press", s.haptics) { s.haptics = it; r++ }
                ToggleRow("Add a space before dictated text", s.autoSpace) { s.autoSpace = it; r++ }
                ToggleRow("Open on the letter keys", s.typingMode, divider = false) { s.typingMode = it; r++ }
            }
            Meta("Mic key: tap to start and tap to finish, or hold while you talk and let go. ABC switches to letter keys; 🎤 there goes back to the mic. 🌐 switches keyboards; hold it to pick one.")
            Meta("WhisperAsh ${BuildConfig.VERSION_NAME}", Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun KeyField(label: String, name: String, help: String?, onSaved: () -> Unit) {
        var has by remember { mutableStateOf(app.secure.has(name)) }
        var value by remember { mutableStateOf("") }
        Column(Modifier.padding(bottom = 12.dp)) {
            if (has) {
                ListRow(label, "Saved on this phone", Ic.key, Tone.Ok, divider = false, trailing = {
                    Btn("Remove", { app.secure.put(name, null); has = false; onSaved() }, kind = BtnKind.Plain)
                })
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    GlassField(value, { value = it.trim() }, Modifier.weight(1f), label = label, placeholder = "Paste the key", password = true)
                    Spacer(Modifier.width(8.dp))
                    Btn("Save", { app.secure.put(name, value); value = ""; has = true; onSaved() }, kind = BtnKind.Tint, enabled = value.length >= 20)
                }
            }
            if (help != null) Meta(help, Modifier.padding(start = 4.dp, top = 4.dp))
        }
    }

    // ---------- History ----------
    @Composable
    private fun HistoryScreen(version: Int, onBack: () -> Unit) {
        var query by remember { mutableStateOf("") }
        var refresh by remember { mutableIntStateOf(0) }
        val items = remember(query, version, refresh) { app.db.history(query) }
        val timings = remember(version) { app.db.recentTimings(10) }
        val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        val busy by HistoryJobs.busy.collectAsState()
        val errors by HistoryJobs.errors.collectAsState()
        val replaced by HistoryJobs.replaced.collectAsState()
        var menuFor by remember { mutableStateOf<HistoryItem?>(null) }
        // The Undo bar stays a few seconds after a re-transcribe replaced a text.
        LaunchedEffect(replaced) { if (replaced != null) { delay(8_000); HistoryJobs.replaced.value = null } }

        fun retranscribe(h: HistoryItem) {
            HistoryJobs.busy.value = HistoryJobs.busy.value + h.id
            HistoryJobs.errors.value = HistoryJobs.errors.value - h.id
            app.scope.launch {
                try {
                    val text = HistoryRetranscriber(app).run(h)
                    app.db.replaceHistoryText(h.id, text)
                    HistoryJobs.replaced.value = h.id
                    app.sync.requestSync(SyncClient.Reason.CHANGE)
                } catch (e: Exception) {
                    HistoryJobs.errors.value = HistoryJobs.errors.value + (h.id to RetranscribeErrors.message(e))
                } finally {
                    HistoryJobs.busy.value = HistoryJobs.busy.value - h.id
                    app.sync.dataVersion.value++
                }
            }
        }
        fun undo(id: String) {
            app.db.restoreHistoryText(id); HistoryJobs.replaced.value = null; refresh++
            app.sync.requestSync(SyncClient.Reason.CHANGE)
        }

        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = bottomClearance() + 60.dp),
        ) {
            item { BackBar("Settings", onBack) }
            item {
                LargeTitle("History")
                Subtitle(counted(app.db.historyCount(), "dictation") + (timings.firstOrNull()?.let { " · last one took %.1f s".format(it.optLong("stop_to_insert_ms") / 1000.0) } ?: ""))
            }
            item { SearchBox(query, { query = it }, "Search dictations") }
            if (items.isEmpty()) item { NoteBox(if (query.isBlank()) "Dictations you make with the WhisperAsh keyboard appear here." else "Nothing matches “$query”.", Tone.Neutral) }
            items(items, key = { it.id }) { h ->
                var expanded by remember { mutableStateOf(false) }
                val hasAudio = remember(h.audioPath) { Retranscribe.canRun(h.audioPath) }
                GlassCard(padding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp), onClick = { expanded = !expanded }) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(h.text, Modifier.weight(1f).padding(top = 4.dp), style = G.Body.copy(fontSize = 16.sp), maxLines = if (expanded) 50 else 3, overflow = TextOverflow.Ellipsis)
                        Icon(Ic.more, "More for this dictation", Modifier.size(40.dp).clip(CircleShape).clickable { menuFor = h }.padding(9.dp), tint = G.Ink2)
                    }
                    Spacer(Modifier.height(6.dp))
                    Meta(df.format(Date(h.createdMs)) + (h.app?.let { " · " + it.substringAfterLast('.') } ?: "") + (h.model?.let { " · $it" } ?: ""))
                    // The last RecordingStore.KEEP dictations keep their recording.
                    if (hasAudio) AudioChip(h.audioPath!!, h.audioMs, Modifier.padding(top = 10.dp))
                    if (h.id in busy) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spinner(); Spacer(Modifier.width(10.dp)); Text("Re-transcribing with ${GeminiMeetingClient.BETTER_LABEL}…", style = G.Sub.copy(color = G.Ai, fontWeight = FontWeight.SemiBold))
                    }
                    errors[h.id]?.let { msg ->
                        NoteBox(msg, Tone.Bad, Modifier.padding(top = 10.dp, end = 8.dp).clickable { HistoryJobs.errors.value = HistoryJobs.errors.value - h.id })
                    }
                    if (expanded && !h.rawText.isNullOrBlank() && h.rawText != h.text) Meta("Heard: ${h.rawText}", Modifier.padding(top = 6.dp))
                }
            }
            item { Meta("The recordings of your last ${RecordingStore.KEEP} dictations stay on this phone (never synced) so you can play or re-transcribe them; older ones are deleted.", Modifier.padding(4.dp)) }
        }
        // Undo bar above the tab bar after a re-transcribe.
        val rid = replaced
        if (rid != null) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Gutter).padding(bottom = bottomClearance() - 4.dp)
                .glass(G.Pill, SolidColor(Color(0xF2FFFFFF))).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Text replaced with the better model's version", Modifier.weight(1f), style = G.Sub.copy(color = G.Ink, fontWeight = FontWeight.SemiBold))
            Btn("Undo", { undo(rid) }, kind = BtnKind.Plain, icon = Ic.undo)
        }
        menuFor?.let { h ->
            val cur = items.firstOrNull { it.id == h.id } ?: h
            GlassSheet("Dictation", { menuFor = null }) {
                if (Retranscribe.canRun(cur.audioPath) && cur.id !in busy) SheetRow("Re-transcribe (better model)", Ic.spark, Tone.Ai,
                    sub = "${GeminiMeetingClient.BETTER_LABEL}, then the usual cleanup. The current text is kept for Undo.") { menuFor = null; retranscribe(cur) }
                if (cur.prevText != null) SheetRow("Restore previous text", Ic.undo, sub = cur.prevText.take(80)) { menuFor = null; undo(cur.id) }
                SheetRow("Copy", Ic.copy) { menuFor = null; copy(cur.text) }
                SheetRow("Delete", Ic.trash, Tone.Bad) {
                    menuFor = null; app.db.deleteHistory(cur.id); refresh++
                    app.sync.requestSync(SyncClient.Reason.CHANGE)
                }
            }
        }
        }
    }

    // ---------- Words ----------
    @Composable
    private fun WordsScreen(version: Int, onBack: () -> Unit) {
        var refresh by remember { mutableIntStateOf(0) }
        val words = remember(version, refresh) { app.db.dictionary() }
        var input by remember { mutableStateOf("") }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = bottomClearance()),
        ) {
            item { BackBar("Settings", onBack) }
            item {
                LargeTitle("Words")
                Subtitle("Names and terms WhisperAsh should spell your way. Synced with your PC.")
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassField(input, { input = it }, Modifier.weight(1f), placeholder = "Add a word (or several, with commas)")
                    Spacer(Modifier.width(8.dp))
                    Btn("Add", {
                        input.split(',').forEach { app.db.addWord(it) }
                        input = ""; refresh++
                        app.sync.requestSync(SyncClient.Reason.CHANGE)
                    }, kind = BtnKind.Primary, enabled = input.isNotBlank())
                }
            }
            item { Meta(counted(words.size, "word"), Modifier.padding(start = 4.dp)) }
            if (words.isNotEmpty()) item {
                GlassList {
                    words.forEachIndexed { i, w ->
                        ListRow(w, divider = i < words.size - 1, trailing = {
                            Btn("Remove", { app.db.removeWord(w); refresh++; app.sync.requestSync(SyncClient.Reason.CHANGE) }, kind = BtnKind.Plain)
                        })
                    }
                }
            }
        }
    }

    // ---------- Account ----------
    @Composable
    private fun AccountScreen() {
        val state by app.sync.state.collectAsState()
        val scope = rememberCoroutineScope()
        var email by remember { mutableStateOf(app.settings.email) }
        var password by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var server by remember { mutableStateOf(app.settings.serverUrl) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var registerMode by remember { mutableStateOf(false) }
        var showAdvanced by remember { mutableStateOf(app.settings.serverUrl != Settings.DEFAULT_SERVER) }
        var forgotMode by remember { mutableStateOf(false) }
        var info by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { app.sync.refreshState() }

        fun run(block: suspend () -> Unit) {
            busy = true; message = ""; info = ""
            scope.launch {
                try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { message = friendly(e.message) }
                busy = false
                app.sync.refreshState()
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.signedIn) {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(Ic.user, Tone.Accent, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.email, style = G.ItemTitle)
                            Meta(if (state.lastSyncAt > 0) "Last sync " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.lastSyncAt)) else "Not synced yet")
                        }
                    }
                    Meta("History ${app.db.historyCount()} · words ${app.db.dictionary().size} · waiting to upload ${state.pending}", Modifier.padding(top = 10.dp))
                    if (state.lastError.isNotEmpty()) NoteBox(state.lastError, Tone.Bad, Modifier.padding(top = 10.dp))
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn(if (state.busy) "Syncing…" else "Sync now", { run { app.sync.syncNow(SyncClient.Reason.MANUAL) } }, kind = BtnKind.Primary, icon = Ic.cloud, enabled = !state.busy)
                        Btn("Log out", { run { app.sync.logout() } }, kind = BtnKind.Glass, icon = Ic.logout)
                    }
                }
                Meta("History, words, notes, meetings, diaries and settings sync with WhisperAsh on your other devices. Your own API keys never leave this phone.")
                var current by remember { mutableStateOf("") }
                var newPassword by remember { mutableStateOf("") }
                SectionTitle("Change password")
                GlassCard {
                    GlassField(current, { current = it }, label = "Current (or temporary) password", password = true)
                    Spacer(Modifier.height(10.dp))
                    GlassField(newPassword, { newPassword = it }, label = "New password (8+ characters)", password = true)
                    Spacer(Modifier.height(12.dp))
                    Btn(if (busy) "Please wait…" else "Change password", {
                        val c = current; val n = newPassword
                        run { app.sync.changePassword(c, n); info = "Password changed. Your other devices were signed out." }
                        current = ""; newPassword = ""
                    }, kind = BtnKind.Tint, enabled = !busy && current.isNotEmpty() && newPassword.length >= 8)
                }
            } else if (forgotMode) {
                GlassCard {
                    Text("Forgot password", style = G.SectionTitle.copy(fontSize = 19.sp))
                    Meta("Send a reset request. The WhisperAsh admin resets your password and sends you a temporary one; sign in with it, then change it here.", Modifier.padding(vertical = 8.dp))
                    GlassField(email, { email = it }, label = "Email", keyboardType = KeyboardType.Email)
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Btn(if (busy) "Please wait…" else "Send reset request", {
                            app.settings.serverUrl = server
                            val e = email
                            run { app.sync.requestPasswordReset(e); info = "Request sent. The admin will send you a temporary password." }
                        }, kind = BtnKind.Primary, enabled = !busy && email.contains("@"))
                        Btn("Back to sign in", { forgotMode = false; info = "" }, kind = BtnKind.Plain)
                    }
                }
            } else {
                GlassCard {
                    Text(if (registerMode) "Create an account" else "Sign in to WhisperAsh", style = G.SectionTitle.copy(fontSize = 19.sp))
                    Meta(if (registerMode) "Free words every day, no API keys needed." else "Use the email and password of your WhisperAsh account.", Modifier.padding(top = 4.dp, bottom = 10.dp))
                    GlassField(email, { email = it }, label = "Email", keyboardType = KeyboardType.Email)
                    Spacer(Modifier.height(10.dp))
                    GlassField(password, { password = it }, label = "Password", password = true)
                    if (registerMode && showAdvanced) {
                        Spacer(Modifier.height(10.dp))
                        GlassField(code, { code = it }, label = "Sign-up code (only if you were given one)")
                    }
                    Spacer(Modifier.height(14.dp))
                    Btn(if (busy) "Please wait…" else if (registerMode) "Create account" else "Sign in", {
                        app.settings.serverUrl = server
                        val e = email; val p = password; val c = code
                        run { if (registerMode) app.sync.register(e, p, c) else app.sync.login(e, p) }
                        password = ""
                    }, Modifier.fillMaxWidth(), BtnKind.Primary, enabled = !busy && email.isNotBlank() && password.length >= 8, big = true)
                    Row(Modifier.padding(top = 6.dp)) {
                        Btn(if (registerMode) "I have an account" else "New account", { registerMode = !registerMode }, kind = BtnKind.Plain)
                        if (!registerMode) Btn("Forgot password?", { forgotMode = true; message = ""; info = "" }, kind = BtnKind.Plain)
                    }
                    if (state.lastError.isNotEmpty() && message.isEmpty()) Meta(state.lastError)
                }
                Btn(if (showAdvanced) "Hide advanced" else "Advanced (own server)", { showAdvanced = !showAdvanced }, kind = BtnKind.Plain, icon = Ic.server)
                if (showAdvanced) GlassCard {
                    GlassField(server, { server = it }, label = "WhisperAsh server URL")
                    Meta("Only change this if you run your own WhisperAsh server (it is open source).", Modifier.padding(top = 6.dp))
                }
            }
            if (message.isNotEmpty()) NoteBox(message, Tone.Bad)
            if (info.isNotEmpty() && message.isEmpty()) NoteBox(info, Tone.Ok)
        }
    }

    private fun friendly(code: String?) = when (code) {
        "invalid_credentials" -> "Wrong email or password."
        "too_many_attempts" -> "Too many tries. Wait 15 minutes."
        "email_taken" -> "That email already has an account. Log in instead."
        "invalid_signup_code" -> "Wrong sign-up code."
        "password_too_short" -> "Password must be at least 8 characters."
        "invalid_email" -> "That email doesn't look right."
        "too_many_signups" -> "Too many new accounts from this network today. Try again tomorrow."
        "too_many_requests" -> "Too many requests from this network today. Try again tomorrow."
        "wrong_current_password" -> "The current password is wrong."
        null -> "Something went wrong."
        else -> code
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("WhisperAsh", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ASK_MIC = "ask_mic"
        const val EXTRA_TAB = "open_tab"
        const val TAB_HOME = 0
        const val TAB_NOTES = 1
        const val TAB_MEETINGS = 2
        const val TAB_DIARIES = 3
        const val TAB_SETTINGS = 4
        const val SUB_ACCOUNT = "account"
        const val SUB_WORDS = "words"
        const val SUB_HISTORY = "history"
        const val EXTRA_NOTE = "note_start"
    }
}
