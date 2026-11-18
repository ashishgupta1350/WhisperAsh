package com.whisperash.android.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.whisperash.android.ApiException
import com.whisperash.android.BuildConfig
import com.whisperash.android.MainActivity
import com.whisperash.android.MicRecorder
import com.whisperash.android.NoSpeechException
import com.whisperash.android.Prompts
import com.whisperash.android.Recording
import com.whisperash.android.SecureStore
import com.whisperash.android.SyncClient
import com.whisperash.android.Wav
import com.whisperash.android.WhisperAshApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The WhisperAsh keyboard: one big mic key (tap to start / tap to stop, or hold and release),
 * plus the few keys you need around dictation, and a full letter layout behind the ABC key.
 * Works in any app through InputConnection, so it needs no Accessibility or overlay permission.
 */
class WhisperAshIme : InputMethodService() {
    private enum class State { IDLE, RECORDING, PROCESSING }

    private val app get() = application as WhisperAshApp
    private val main = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var recorder: MicRecorder? = null
    private var job: Job? = null
    private var downAt = 0L
    private var startedThisTouch = false
    private var targetPkg: String? = null
    private var lastInsert: String? = null
    private var passwordField = false

    private lateinit var mic: MicButton
    private lateinit var status: TextView
    private lateinit var scriptChip: TextView
    private lateinit var undoKey: TextView
    private lateinit var cancelKey: TextView
    private lateinit var enterKey: TextView
    private lateinit var topMic: TextView
    private var debugReceiver: BroadcastReceiver? = null

    // Typing keyboard
    private lateinit var voicePanel: FrameLayout
    private lateinit var lettersPanel: LinearLayout
    private lateinit var bottomRow: LinearLayout
    private var typingMode = false
    private var symbols = false
    private var shift = 0 // 0 off, 1 next letter, 2 caps lock
    private var lastShiftTap = 0L
    private var enterLabel = "↵"
    private val letterKeys = ArrayList<Pair<TextView, String>>()
    private var shiftKey: TextView? = null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) registerDebugHooks()
    }

    override fun onDestroy() {
        debugReceiver?.let { unregisterReceiver(it) }
        recorder?.cancel()
        super.onDestroy()
    }

    // ---------- view ----------
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        // Top bar: script chip | status | (mic in letter mode) | settings
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        scriptChip = chip("Hinglish") { cycleScript() }
        status = TextView(this).apply {
            setTextColor(0xFFB4B5C0.toInt()); textSize = 13f; maxLines = 2
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { openApp(null) }
        }
        topMic = iconKey("🎤") {
            setTypingMode(false)
            if (state == State.IDLE && !passwordField && hasMic() && hasKey()) startRecording()
        }.apply { contentDescription = "Dictate" }
        val gear = iconKey("⚙") { openApp(null) }.apply { contentDescription = "WhisperAsh settings" }
        top.addView(scriptChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { leftMargin = dp(4) })
        top.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(topMic, LinearLayout.LayoutParams(dp(46), dp(40)))
        top.addView(gear, LinearLayout.LayoutParams(dp(46), dp(40)))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        // Middle: the voice panel (cancel | mic | undo) or the letter keys, same height so nothing jumps.
        val middle = FrameLayout(this)
        voicePanel = FrameLayout(this)
        mic = MicButton(this).apply {
            contentDescription = "Dictate"
            setOnTouchListener { _, ev -> onMicTouch(ev); true }
        }
        voicePanel.addView(mic, FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER))
        cancelKey = key("✕  Cancel", 14f, FN) { cancelRecording() }.apply { visibility = View.INVISIBLE }
        voicePanel.addView(cancelKey, FrameLayout.LayoutParams(dp(104), dp(46), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(8) })
        undoKey = key("↶  Undo", 14f, FN) { undoLastInsert() }.apply { visibility = View.INVISIBLE }
        voicePanel.addView(undoKey, FrameLayout.LayoutParams(dp(104), dp(46), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(8) })
        middle.addView(voicePanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        lettersPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        middle.addView(lettersPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP * 3)))

        // Bottom row, rebuilt per mode (voice: ABC , 🌐 space . ? ⌫ ↵ — letters: ?123 , 🌐 space . ↵).
        bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(bottomRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)))
        typingMode = app.settings.typingMode
        applyMode()

        // Keep the keys above the navigation bar (and the system's IME back / switch buttons).
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(dp(4), dp(4), dp(4), dp(4) + bottom)
            insets
        }
        refreshIdleStatus()
        return root
    }

    // ---------- typing keyboard ----------
    private fun applyMode() {
        voicePanel.visibility = if (typingMode) View.GONE else View.VISIBLE
        lettersPanel.visibility = if (typingMode) View.VISIBLE else View.GONE
        topMic.visibility = if (typingMode) View.VISIBLE else View.GONE
        if (typingMode) buildLetters()
        buildBottomRow()
    }

    private fun setTypingMode(on: Boolean) {
        if (typingMode == on) return
        typingMode = on
        app.settings.typingMode = on
        symbols = false
        updateAutoCaps()
        applyMode()
        if (::status.isInitialized && state == State.IDLE) refreshIdleStatus()
    }

    // Keys fill their whole cell with no margins, so the gaps between keys still count as the
    // nearest key; the gap is only drawn (see keyBackground's inset).
    private fun rowParams(w: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)

    private fun buildBottomRow() {
        bottomRow.removeAllViews()
        fun add(v: View, w: Float) = bottomRow.addView(v, rowParams(w))
        val globe = key("🌐", 17f, FN, onLong = { showPicker() }) { switchKeyboard() }.apply {
            contentDescription = "Switch keyboard"
        }
        val comma = key(",", 20f, FN) { typeText(",") }
        val space = key("", 14f, KEY, onLong = { showPicker() }) { typeText(" ") }.apply {
            contentDescription = "Space"
        }
        val period = key(".", 20f, FN, onLong = { typeText("?") }) { typeText(".") }
        enterKey = key(enterLabel, 18f, ACCENT, pill = true) { pressEnter() }.apply { contentDescription = "Enter" }
        if (typingMode) {
            val sym = key(if (symbols) "ABC" else "?123", 14f, FN, pill = true) { symbols = !symbols; buildLetters(); buildBottomRow() }
            add(sym, 1.5f); add(comma, 1f); add(globe, 1f); add(space, 4.4f); add(period, 1f); add(enterKey, 1.5f)
        } else {
            val abc = key("ABC", 14f, FN, pill = true) { setTypingMode(true) }.apply { contentDescription = "Type with keys" }
            val question = key("?", 20f, FN) { typeText("?") }
            val backspace = key("⌫", 19f, FN) {}.apply { contentDescription = "Delete" }
            setupRepeat(backspace)
            add(abc, 1.4f); add(comma, 0.9f); add(globe, 1f); add(space, 3.2f); add(period, 0.9f); add(question, 0.9f); add(backspace, 1.2f); add(enterKey, 1.4f)
        }
    }

    private fun buildLetters() {
        lettersPanel.removeAllViews()
        letterKeys.clear()
        shiftKey = null
        val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
        rows.forEachIndexed { i, chars ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            fun add(v: View, w: Float) = row.addView(v, rowParams(w))
            if (i == 2) {
                val s = if (symbols) key("=", 19f, FN) { typeText("=") }
                else key("⇧", 20f, FN) { tapShift() }.also { shiftKey = it; it.contentDescription = "Shift" }
                add(s, 1.5f)
            }
            val rowKeys = chars.mapIndexed { j, c ->
                // Top row letters carry their number in the corner; hold to type it.
                val hint = if (i == 0 && !symbols) DIGITS[j].toString() else null
                val (view, label) = letterKey(c, hint)
                letterKeys += label to c
                Triple(view, c, hint)
            }
            // The a–l row is indented by half a key: those side strips type "a" and "l" (no dead zones).
            fun sideStrip(target: Triple<View, String, String?>) = View(this).also { strip ->
                attachTap(strip, visual = target.first) { typeLetter(target.second) }
            }
            if (i == 1 && !symbols) row.addView(sideStrip(rowKeys.first()), rowParams(0.5f))
            rowKeys.forEach { add(it.first, 1f) }
            if (i == 1 && !symbols) row.addView(sideStrip(rowKeys.last()), rowParams(0.5f))
            if (i == 2) {
                val back = key("⌫", 19f, FN) {}.apply { contentDescription = "Delete" }
                setupRepeat(back)
                add(back, 1.5f)
            }
            lettersPanel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        refreshLetterLabels()
    }

    /** A letter key: the letter in the middle, an optional number hint in the top-right corner. */
    private fun letterKey(c: String, hint: String?): Pair<View, TextView> {
        val frame = FrameLayout(this).apply {
            background = keyBackground(KEY, KEY_PRESSED, false)
            isHapticFeedbackEnabled = true
            contentDescription = c
        }
        attachTap(frame, onLong = hint?.let { h -> { typeText(h) } }) { typeLetter(c) }
        val label = TextView(this).apply {
            text = c; textSize = 23f; gravity = Gravity.CENTER; setTextColor(KEY_TEXT)
        }
        frame.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (hint != null) {
            frame.addView(TextView(this).apply {
                text = hint; textSize = 10f; setTextColor(HINT_TEXT)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(1); rightMargin = dp(4)
            })
        }
        return frame to label
    }

    private fun refreshLetterLabels() {
        val upper = shift > 0 && !symbols
        for ((k, c) in letterKeys) k.text = if (upper) c.uppercase() else c
        shiftKey?.apply {
            text = if (shift == 2) "⇪" else "⇧"
            when (shift) {
                0 -> { background = keyBackground(FN, FN_PRESSED, false); setTextColor(KEY_TEXT) }
                1 -> { background = keyBackground(FN_PRESSED, FN, false); setTextColor(KEY_TEXT) }
                else -> { background = keyBackground(ACCENT, FN_PRESSED, false); setTextColor(ACCENT_TEXT) }
            }
        }
    }

    private fun tapShift() {
        val now = System.currentTimeMillis()
        shift = when {
            shift == 0 -> 1
            shift == 1 && now - lastShiftTap < 400 -> 2
            else -> 0
        }
        lastShiftTap = now
        refreshLetterLabels()
    }

    private fun typeLetter(c: String) {
        typeText(if (shift > 0 && !symbols) c.uppercase() else c)
        if (shift == 1) { shift = 0; refreshLetterLabels() }
    }

    /** Capital letter at the start of a sentence, where the text field asks for it. */
    private fun updateAutoCaps() {
        if (shift == 2) return
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: 0
        val caps = type != 0 && ic.getCursorCapsMode(type) != 0
        val next = if (caps) 1 else 0
        if (next != shift) { shift = next; if (typingMode && ::lettersPanel.isInitialized) refreshLetterLabels() }
    }

    // ---------- styling ----------
    private fun shape(color: Int, pill: Boolean) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(if (pill) 24 else 7).toFloat()
    }

    // The key shape is drawn inset inside its cell: the visible gap between keys is part of the key's touch area.
    private fun keyBackground(color: Int, pressed: Int, pill: Boolean) = StateListDrawable().apply {
        fun inset(d: android.graphics.drawable.Drawable) = android.graphics.drawable.InsetDrawable(d, dp(3), dp(6), dp(3), dp(5))
        addState(intArrayOf(android.R.attr.state_pressed), inset(shape(pressed, pill)))
        addState(intArrayOf(), inset(shape(color, pill)))
    }

    /**
     * Gboard-style touch handling: vibrate on touch-down, act on lift wherever the finger ended up (a slight
     * roll off the key used to cancel the press), hold for [onLong]. Each key handles its own finger, so fast
     * overlapping taps on two keys both count.
     */
    @SuppressLint("ClickableViewAccessibility")
    // Fast two-thumb typing: the next finger often lands before the last one lifts. Like the stock
    // keyboards, a new press types the key that is still held first, so letters keep the order they
    // were pressed in and none are lost (also when both fingers hit the same key).
    private class Press(val onTap: () -> Unit, val longPress: Runnable) {
        var done = false
    }
    private var heldPress: Press? = null

    private fun finish(p: Press, type: Boolean) {
        main.removeCallbacks(p.longPress)
        if (!p.done) {
            p.done = true
            if (type) p.onTap()
        }
        if (heldPress === p) heldPress = null
    }

    private fun attachTap(view: View, visual: View = view, onLong: (() -> Unit)? = null, onTap: () -> Unit) {
        val presses = HashMap<Int, Press>()
        view.setOnTouchListener { _, ev ->
            val id = ev.getPointerId(ev.actionIndex)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    heldPress?.let { finish(it, true) }
                    lateinit var p: Press
                    p = Press(onTap, Runnable {
                        if (!p.done) { p.done = true; haptic(visual); onLong?.invoke() }
                        if (heldPress === p) heldPress = null
                    })
                    presses.remove(id)?.let { finish(it, true) }
                    presses[id] = p
                    heldPress = p
                    visual.isPressed = true
                    haptic(visual)
                    if (onLong != null) main.postDelayed(p.longPress, 420)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    presses.remove(id)?.let { finish(it, true) }
                    visual.isPressed = presses.isNotEmpty()
                }
                MotionEvent.ACTION_CANCEL -> {
                    presses.values.forEach { finish(it, false) }
                    presses.clear()
                    visual.isPressed = false
                }
            }
            true
        }
        // TalkBack and other accessibility services still activate keys with a click.
        view.setOnClickListener { onTap() }
    }

    private fun key(
        label: String, size: Float, color: Int = KEY, pill: Boolean = false,
        onLong: (() -> Unit)? = null, onClick: () -> Unit,
    ) = TextView(this).apply {
        text = label; textSize = size; gravity = Gravity.CENTER
        val pressed = when (color) { KEY -> KEY_PRESSED; FN -> FN_PRESSED; else -> 0xFFD3D7EA.toInt() }
        setTextColor(if (color == ACCENT) ACCENT_TEXT else KEY_TEXT)
        background = keyBackground(color, pressed, pill)
        isHapticFeedbackEnabled = true
        attachTap(this, onLong = onLong, onTap = onClick)
    }

    /** A borderless toolbar button, like the icons above the stock keyboard. */
    private fun iconKey(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(KEY_TEXT)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(FN, true))
            addState(intArrayOf(), shape(0x00000000, true))
        }
        setOnClickListener { haptic(this); onClick() }
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFB38A.toInt()); background = shape(FN, true); setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun haptic(v: View) {
        if (app.settings.haptics) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRepeat(v: TextView) {
        val repeat = object : Runnable {
            override fun run() { deleteBackward(); main.postDelayed(this, 60) }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { view.isPressed = true; haptic(view); deleteBackward(); main.postDelayed(repeat, 420) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; main.removeCallbacks(repeat) }
            }
            true
        }
    }

    // ---------- lifecycle ----------
    // A physical keyboard (or the emulator's) would hide us by default, but the mic is still wanted.
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        passwordField = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        if (!restarting) setUndo(null)
        enterLabel = when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "↵"
        }.let { if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) "↵" else it }
        if (::enterKey.isInitialized) enterKey.text = enterLabel
        updateAutoCaps()
        if (state == State.IDLE) refreshIdleStatus()
        app.sync.requestSync(SyncClient.Reason.OPEN)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // The keyboard went away mid-recording: finish the dictation; the text goes to the clipboard.
        if (state == State.RECORDING) stopAndProcess()
        super.onFinishInputView(finishingInput)
    }

    private fun refreshIdleStatus() {
        if (!::status.isInitialized) return
        scriptChip.text = when (app.settings.hindiScript) {
            "devanagari" -> "हिन्दी"
            "english" -> "English"
            else -> "Hinglish"
        }
        mic.mode = MicButton.Mode.IDLE
        cancelKey.visibility = View.INVISIBLE
        status.text = when {
            passwordField -> "Dictation is off in password fields"
            !hasMic() -> "Tap here to allow the microphone"
            !hasKey() -> if (app.useAccount()) "Tap here to sign in to WhisperAsh" else "Tap here to add your Groq key"
            typingMode -> ""
            else -> "Tap the mic, or hold it while you talk"
        }
        if (passwordField || !hasMic() || !hasKey()) mic.mode = MicButton.Mode.DISABLED
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasKey() = app.canDictate()

    private fun openApp(extra: String?) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (extra != null) putExtra(extra, true)
        })
    }

    // ---------- mic ----------
    private fun onMicTouch(ev: MotionEvent) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downAt = System.currentTimeMillis()
                startedThisTouch = false
                when (state) {
                    State.IDLE -> {
                        if (passwordField) return
                        if (!hasMic()) { openApp(MainActivity.EXTRA_ASK_MIC); return }
                        if (!hasKey()) { openApp(null); return }
                        startRecording()
                        startedThisTouch = true
                    }
                    State.RECORDING -> stopAndProcess()
                    State.PROCESSING -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                // Held for a while = push-to-talk: letting go finishes. A quick tap keeps listening.
                if (startedThisTouch && state == State.RECORDING && System.currentTimeMillis() - downAt > HOLD_MS) stopAndProcess()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            if (state != State.RECORDING) return
            if (!r.isRunning) { stopAndProcess(); return } // hit the length cap
            mic.level = r.level.toFloat()
            val secs = (System.currentTimeMillis() - r.startedAt) / 1000
            status.text = "Listening…  %d:%02d   (tap to finish)".format(secs / 60, secs % 60)
            main.postDelayed(this, 50)
        }
    }

    private fun startRecording() {
        targetPkg = currentInputEditorInfo?.packageName
        val r = MicRecorder(cacheDir)
        try {
            r.start()
        } catch (e: Exception) {
            status.text = e.message ?: "Microphone unavailable"
            return
        }
        recorder = r
        state = State.RECORDING
        mic.mode = MicButton.Mode.RECORDING
        cancelKey.visibility = View.VISIBLE
        setUndo(null)
        haptic(mic)
        app.scope.launch { app.groq.warmUp() }
        main.post(ticker)
    }

    private fun cancelRecording() {
        if (state != State.RECORDING) return
        recorder?.cancel()
        recorder = null
        state = State.IDLE
        refreshIdleStatus()
        status.text = "Cancelled"
    }

    private fun stopAndProcess() {
        val r = recorder ?: return
        recorder = null
        val stopAt = System.currentTimeMillis()
        haptic(mic)
        runPipeline(stopAt) { r.stop() }
    }

    private fun runPipeline(stopAt: Long, source: () -> Recording) {
        state = State.PROCESSING
        mic.mode = MicButton.Mode.BUSY
        cancelKey.visibility = View.INVISIBLE
        status.text = "Transcribing…"
        val pkg = targetPkg
        job = app.scope.launch {
            try {
                val rec = source()
                val result = app.engine.process(rec, pkg, stopAt) { stage ->
                    main.post { if (state == State.PROCESSING) status.text = stage }
                }
                val insertedAt = withContext(Dispatchers.Main) {
                    insert(result.text)
                    val t = System.currentTimeMillis()
                    state = State.IDLE
                    refreshIdleStatus()
                    status.text = "✓ %.1f s".format((t - stopAt) / 1000.0) + (result.note?.let { " · $it" } ?: "")
                    t
                }
                app.engine.record(result, rec.durationMs, pkg, insertedAt, stopAt)
                if (BuildConfig.DEBUG) Log.i(TAG, "RESULT " + org.json.JSONObject().put("text", result.text)
                    .put("raw", result.rawText).put("timings", result.timings).put("pkg", pkg ?: "").toString())
            } catch (e: Exception) {
                val msg = when (e) {
                    is NoSpeechException -> "Didn't catch any speech. Try again."
                    is ApiException -> e.message ?: "Transcription failed"
                    else -> "Error: ${e.message ?: e.javaClass.simpleName}"
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "ERROR $msg", e)
                withContext(Dispatchers.Main) {
                    state = State.IDLE
                    refreshIdleStatus()
                    status.text = msg
                }
            }
        }
    }

    // ---------- text ----------
    private fun insert(text: String) {
        if (text.isEmpty()) return
        val ic = currentInputConnection
        if (ic == null || !isInputViewShown) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("WhisperAsh", text))
            Toast.makeText(this, "WhisperAsh: text copied", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = ic.getSelectedText(0)
        val prefix = if (selected.isNullOrEmpty() && app.settings.autoSpace) Prompts.leadingSpace(ic.getTextBeforeCursor(1, 0)) else ""
        val out = prefix + text
        ic.beginBatchEdit()
        ic.commitText(out, 1)
        ic.endBatchEdit()
        setUndo(out)
    }

    private fun setUndo(text: String?) {
        lastInsert = text
        if (::undoKey.isInitialized) undoKey.visibility = if (text != null) View.VISIBLE else View.INVISIBLE
    }

    private fun undoLastInsert() {
        val t = lastInsert ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(t.length, 0)?.toString()
        if (before == t) ic.deleteSurroundingText(t.length, 0)
        else status.text = "Can't undo: the text was changed"
        setUndo(null)
    }

    private fun typeText(s: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(s, 1)
        setUndo(null)
        if (typingMode) updateAutoCaps()
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        setUndo(null)
        if (typingMode) updateAutoCaps()
    }

    private fun pressEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val multiLine = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (!noAction && !multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        setUndo(null)
    }

    private fun switchKeyboard() {
        if (!switchToPreviousInputMethod()) showPicker()
    }

    private fun showPicker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
    }

    private fun cycleScript() {
        val order = listOf("roman", "devanagari", "english")
        val next = order[(order.indexOf(app.settings.hindiScript) + 1) % order.size]
        app.settings.setSynced("hindiScriptPreference", next)
        if (state == State.IDLE) refreshIdleStatus()
        status.text = when (next) {
            "devanagari" -> "Hindi words in Devanagari"
            "english" -> "Everything in English"
            else -> "Hinglish in Roman script"
        }
    }

    // ---------- debug-only test hooks (not in release builds) ----------
    private fun registerDebugHooks() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_DICTATE_FILE -> {
                        if (state != State.IDLE) return
                        val f = File(getExternalFilesDir("test"), intent.getStringExtra("file") ?: return)
                        targetPkg = currentInputEditorInfo?.packageName
                        val stopAt = System.currentTimeMillis()
                        runPipeline(stopAt) { Wav.toRecording(f, cacheDir) }
                    }
                    ACTION_MIC -> {
                        val ms = intent.getLongExtra("ms", 3000)
                        if (state == State.IDLE && hasMic()) {
                            startRecording()
                            main.postDelayed({ if (state == State.RECORDING) stopAndProcess() }, ms)
                        }
                    }
                    ACTION_TTS -> makeTtsClip(
                        intent.getStringExtra("text") ?: return, intent.getStringExtra("lang") ?: "hi-IN",
                        intent.getStringExtra("file") ?: "tts.wav",
                    )
                    ACTION_SET -> {
                        val key = intent.getStringExtra("key") ?: return
                        app.settings.setSynced(key, intent.getStringExtra("value"))
                        refreshIdleStatus()
                        Log.i(TAG, "SET $key=${intent.getStringExtra("value")}")
                    }
                    ACTION_TOUCH -> injectTouches(intent.getStringExtra("seq") ?: return)
                    ACTION_NOTE_TEXT -> {
                        // Same step the Notes tab runs after speech-to-text: words -> titled bullet note.
                        val f = File(getExternalFilesDir("test"), intent.getStringExtra("file") ?: return)
                        app.scope.launch(Dispatchers.IO) {
                            try {
                                val t0 = System.currentTimeMillis()
                                val n = app.notes.create(f.readText())
                                Log.i(TAG, "NOTE " + org.json.JSONObject().put("file", f.name).put("ms", System.currentTimeMillis() - t0)
                                    .put("title", n.title).put("body", n.body).toString())
                            } catch (e: Exception) {
                                Log.i(TAG, "NOTE_ERROR ${f.name} ${e.message}")
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_DICTATE_FILE); addAction(ACTION_MIC); addAction(ACTION_SET); addAction(ACTION_TTS); addAction(ACTION_TOUCH); addAction(ACTION_NOTE_TEXT)
        }
        // Only senders holding DUMP may trigger these: adb shell has it, ordinary apps cannot get it.
        ContextCompat.registerReceiver(this, r, filter, Manifest.permission.DUMP, null, ContextCompat.RECEIVER_EXPORTED)
        debugReceiver = r
    }

    // Debug only: multi-finger touches on the keyboard, e.g. "d0 486 1796;d1 646 1946;u1;u0"
    // (d<pointer> x y = finger down at screen pixels, u<pointer> = lift). adb's `input tap` is single-finger.
    private fun injectTouches(seq: String) {
        val root = window?.window?.decorView ?: return
        val loc = IntArray(2).also { root.getLocationOnScreen(it) }
        val down = System.currentTimeMillis()
        val active = LinkedHashMap<Int, Pair<Float, Float>>()
        for ((i, step) in seq.split(';').map { it.trim() }.filter { it.isNotEmpty() }.withIndex()) {
            val parts = step.substring(1).trim().split(Regex("\\s+"))
            val pid = parts[0].toInt()
            val action: Int
            if (step[0] == 'd') {
                active[pid] = (parts[1].toFloat() - loc[0]) to (parts[2].toFloat() - loc[1])
                val idx = active.keys.indexOf(pid)
                action = if (active.size == 1) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            } else {
                val idx = active.keys.indexOf(pid)
                action = if (active.size == 1) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_POINTER_UP or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
            val ids = active.keys.toList()
            val props = Array(ids.size) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(ids.size) { MotionEvent.PointerCoords().apply { x = active[ids[it]]!!.first; y = active[ids[it]]!!.second; pressure = 1f; size = 1f } }
            val ev = MotionEvent.obtain(down, down + i * 40L, action, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            root.dispatchTouchEvent(ev)
            ev.recycle()
            if (step[0] == 'u') active.remove(pid)
        }
        Log.i(TAG, "TOUCH done: $seq")
    }

    // Debug only: speaks a sentence with the phone's own TTS into a WAV, for Hindi test clips.
    private fun makeTtsClip(text: String, lang: String, name: String) {
        var tts: android.speech.tts.TextToSpeech? = null
        tts = android.speech.tts.TextToSpeech(this) { st ->
            val t = tts ?: return@TextToSpeech
            if (st != android.speech.tts.TextToSpeech.SUCCESS) { Log.i(TAG, "TTS init failed"); return@TextToSpeech }
            val res = t.setLanguage(java.util.Locale.forLanguageTag(lang))
            Log.i(TAG, "TTS language $lang -> $res")
            val out = File(getExternalFilesDir("test"), name)
            t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { Log.i(TAG, "TTS done ${out.name} ${out.length()}"); t.shutdown() }
                @Deprecated("") override fun onError(id: String?) { Log.i(TAG, "TTS error"); t.shutdown() }
            })
            t.synthesizeToFile(text, null, out, "clip")
        }
    }

    companion object {
        const val TAG = "WhisperAsh"
        const val HOLD_MS = 450L
        // Dark keyboard palette (same feel as the stock Android keyboard in dark mode).
        const val BG = 0xFF181920.toInt()
        const val KEY = 0xFF2A2C34.toInt()
        const val KEY_PRESSED = 0xFF3A3D47.toInt()
        const val FN = 0xFF353B4D.toInt()
        const val FN_PRESSED = 0xFF454C63.toInt()
        const val ACCENT = 0xFFB9BED5.toInt()
        const val ACCENT_TEXT = 0xFF1A1C24.toInt()
        const val KEY_TEXT = 0xFFE4E5F0.toInt()
        const val HINT_TEXT = 0xFF81828B.toInt()
        const val ROW_DP = 58
        const val ACTION_DICTATE_FILE = "com.whisperash.android.DEBUG_DICTATE_FILE"
        const val ACTION_MIC = "com.whisperash.android.DEBUG_MIC"
        const val ACTION_SET = "com.whisperash.android.DEBUG_SET"
        const val ACTION_TTS = "com.whisperash.android.DEBUG_TTS"
        const val ACTION_TOUCH = "com.whisperash.android.DEBUG_TOUCH"
        const val ACTION_NOTE_TEXT = "com.whisperash.android.DEBUG_NOTE_TEXT"
        val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { r -> r.map { it.toString() } }
        val SYMBOL_ROWS = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "₹", "_", "&", "-", "+", "(", ")", "/"),
            listOf("*", "\"", "'", ":", ";", "!", "?"),
        )
        const val DIGITS = "1234567890"
    }
}
