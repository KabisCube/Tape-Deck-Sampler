package com.tapedecksampler.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity(), AudioEngine.Listener {
    enum class Mode { HOME, PAGES, PAGE_COPIED, PAGE_PASTE, PAGE_RESET, MENU, ADD_SAMPLE, DEVICE_RECORD, EXPORT, SYSTEM, BACK, SYNC, SYNC_CLAP, VIBRATION, SENSITIVITY, IMPORT_START, IMPORT_END, PROJECTS, PROJECT_OPEN, PROJECT_EDIT, PROJECT_EDIT_MENU, PROJECT_COLOR, PROJECT_DELETE, EXPORT_LOOPS, EFFECTS, FX_ADD, FX_REORDER, FX_VALUE, START, END, RECORD_POSITION, RECORDING, IMPORT_POSITION, TEMPO, BPM, SWING }

    fun interface Work { @Throws(Exception::class) fun run() }

    private class DeletedEffect(val group: Int, val position: Int, val before: Sequencer.Effects)

    private class LoadedSession(val entry: SessionStore.Entry, val preferences: SharedPreferences, val tape: Tape) {
        val sequencer = Sequencer(tape)
        var track = 0
        lateinit var regions: SampleRegions
    }

    @JvmField var mode = Mode.HOME
    lateinit var tape: Tape
    lateinit var sequencer: Sequencer
    lateinit var audio: AudioEngine
    lateinit var instrument: InstrumentView
    @JvmField var track = 0
    @JvmField var selection = 0
    @JvmField var copiedAt = 0L
    @JvmField var pageClipboard: Sequencer.Page? = null
    @JvmField var swapSource = -1
    @JvmField var pasteTarget = -1
    @JvmField var pageAction = 0
    @JvmField var pageClipboardSource = -1
    @JvmField var pagePrompt = false

    fun pageMenu() = mode == Mode.PAGES || mode == Mode.PAGE_COPIED || mode == Mode.PAGE_PASTE || mode == Mode.PAGE_RESET

    fun pagePromptText() = when (selection) {
        0 -> "SELECT A PAGE TO COPY"
        1 -> if (swapSource < 0) "SELECT A PAGE" else "SELECT SECOND PAGE"
        else -> "SELECT A PAGE TO RESET"
    }

    fun pageCopying() = mode == Mode.PAGE_COPIED || mode == Mode.PAGE_PASTE
    fun canOpenPages() = !busy && mode == Mode.HOME

    fun wheelPages() {
        if (effectStackPage()) {
            val count = effectStack().size
            if (count > 0) { mode = Mode.FX_REORDER; effectHeld = -1; selection = min(selection, count - 1); haptic(); instrument.invalidate() }
            return
        }
        if (!canOpenPages()) return
        cancelLive()
        mode = Mode.PAGES; selection = 0; pageAction = 0; swapSource = -1; pagePrompt = false
        haptic(); instrument.invalidate()
    }

    private fun applyPaste() {
        pageClipboard?.let { sequencer.replacePage(pasteTarget, it) }
        mode = Mode.PAGE_COPIED; selection = pageAction; pasteTarget = -1; copiedAt = 0
        save(); preparePlayback()
    }

    private fun applyReset() {
        sequencer.clearPage(pasteTarget)
        mode = Mode.PAGES; selection = pageAction; pasteTarget = -1
        save()
    }

    fun togglePage(index: Int) {
        if (mode != Mode.PAGES) return
        val page = sequencer.page(index)
        page.enabled = !page.enabled
        save(); haptic()
    }

    private fun pagePad(index: Int) {
        val action = if (pagePrompt) selection else -1
        pagePrompt = pagePrompt && action == 1 && swapSource < 0
        when (action) {
            -1 -> { sequencer.selectPage(index); mode = Mode.HOME; stepTarget = -1 }
            0 -> {
                pageClipboard = sequencer.page(index).copy(); pageClipboardSource = index
                mode = Mode.PAGE_COPIED; copiedAt = SystemClock.uptimeMillis()
            }
            1 -> if (swapSource < 0) swapSource = index else { sequencer.swapPages(swapSource, index); swapSource = -1 }
            2 -> { pasteTarget = index; mode = Mode.PAGE_RESET }
        }
        save()
        if (mode == Mode.HOME) preparePlayback()
    }

    @JvmField var start = 0
    @JvmField var end = Sequencer.DEFAULT_SLICE_FRAMES
    @JvmField var cursor = 0
    @JvmField var recordStart = 0
    @Volatile @JvmField var recordPosition = 0
    @Volatile @JvmField var waveVersion = 0
    @JvmField var fine = false
    @JvmField var zoom = true
    private var zoomFrames = DEFAULT_TIMELINE_FRAMES.toLong()
    private var autoHomeZoom = true
    private var homeWindowStart = 0L
    @JvmField var busy = false
    @JvmField var effectParameter = 0
    @JvmField var effectGroup = -1
    @JvmField var effectHeld = -1
    private var exportPasses = 1
    private var effectDirect = false
    private val deletedEffects = ArrayList<DeletedEffect>()
    private var effectReturnSelection = 0
    @JvmField var effectValue = 0
    private var effectSnapshot: Sequencer.Effects? = null
    private var effectHadOverride = false
    @JvmField var stepTarget = -1
    private var stepFromHold = false
    private var sliceDraft = false
    private var sliceBeforeStart = 0
    private var sliceBeforeEnd = 0
    private var sliceSnapshot: Sequencer.Effects? = null
    private val liveWorker: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var liveGeneration = 0
    private var liveTask: Future<*>? = null
    private val liveUpdate = Runnable { processLive() }
    @JvmField var wheelSensitivity = 0
    @JvmField var darkTheme = false
    @JvmField var leftHandMode = false
    @JvmField var transFlag = false
    @JvmField var prideFlag = false
    @JvmField var transFlagUnlocked = false
    @JvmField var prideFlagUnlocked = false
    @JvmField var syncEnabled = false
    @JvmField var statMillis = 0L
    @JvmField var statPads = 0L
    @JvmField var statJams = 0L
    @JvmField var statWheelTurns = 0.0
    @JvmField var stickerSeed = 0L
    lateinit var batteries: BatteryBay
    private var deadMode: Mode? = null
    private var deadSelection = 0
    private var batteryTickAt = 0L
    private var batterySavedAt = 0L
    private val batteryTick = object : Runnable {
        override fun run() {
            tickBatteries()
            if (foreground && ready) instrument.postDelayed(this, BATTERY_TICK_MS)
        }
    }
    private var resumedAt = 0L
    @JvmField var pendingName = ""
    @JvmField var editTarget: SessionStore.Entry? = null
    @JvmField var colorForNew = false
    @JvmField var clapClock: ClapClock? = null
    private var clapBootOrigin = 0L
    private var clapCapture: ClapCapture? = null
    private var clapGeneration = 0
    @JvmField var clapStatus = ""
    private var wheelBefore = 0
    @JvmField var newSessionDialog: AlertDialog? = null
    @JvmField var message = "READY / ${Tape.SECONDS} SECOND SAMPLE TAPE"
    @JvmField var importName = ""
    @JvmField var imported: ShortArray? = null
    @JvmField var importSource: SampleDecoder.Source? = null
    @JvmField var importStart = 0L
    @JvmField var importEnd = 0L
    @JvmField var importId: String? = null
    @JvmField var importColor = 0
    @JvmField var importWave: WaveSummary? = null
    lateinit var regions: SampleRegions
    private var previewSource: SampleDecoder.Source? = null
    private var previewStart = 0L
    private var previewEnd = 0L
    private var sourcePreview: ShortArray? = null
    private var recordingRegions: SampleRegions? = null
    private var recordingId: String? = null
    @JvmField var sessions = ArrayList<SessionStore.Entry>()
    lateinit var session: SessionStore.Entry
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var sessionStore: SessionStore
    private var preparedSession: LoadedSession? = null
    private lateinit var settings: SharedPreferences
    private lateinit var preferences: SharedPreferences
    private lateinit var audioManager: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var foreground = false
    private var captureReceiverRegistered = false
    private var deviceCaptureStarting = false
    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { deviceCaptureStarting = false; refreshDeviceCapture() }
    }
    private var tempoBefore = 0
    private var swingBefore = 0
    @JvmField var vibrationIntensity = 0
    private var vibrationBefore = 0
    private var vibrator: Vibrator? = null
    private var vibrationEffect: VibrationEffect? = null
    private var vibrationAttributes: VibrationAttributes? = null
    private var lastHaptic = 0L
    private val hapticAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    private val ready get() = ::instrument.isInitialized

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = getSharedPreferences("tapedecksampler", MODE_PRIVATE)
        wheelSensitivity = settings.getInt("wheelSensitivity", DEFAULT_WHEEL).coerceIn(MIN_WHEEL, MAX_WHEEL)
        darkTheme = settings.getBoolean("darkTheme", (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        leftHandMode = settings.getBoolean("leftHandMode", false)
        for (secret in 0 until SECRETS) {
            secretOn[secret] = settings.getBoolean(SECRET_KEYS[secret], false)
            secretUnlocked[secret] = settings.getBoolean(SECRET_KEYS[secret] + "Unlocked", false)
        }
        transFlag = secretOn[SECRET_TRANS]; prideFlag = secretOn[SECRET_PRIDE]; transFlagUnlocked = secretUnlocked[SECRET_TRANS]; prideFlagUnlocked = secretUnlocked[SECRET_PRIDE]
        statMillis = settings.getLong("statMillis", 0)
        statPads = settings.getLong("statPads", 0)
        statJams = settings.getLong("statJams", 0)
        statWheelTurns = settings.getFloat("statWheelTurns", 0f).toDouble()
        if (!settings.contains("stickerSeed")) settings.edit().putLong("stickerSeed", Random().nextLong()).apply()
        stickerSeed = settings.getLong("stickerSeed", 0)
        batteries = BatteryBay(settings)
        applySystemTheme()
        vibrator = getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= 33) vibrationAttributes = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()
        setVibrationIntensity(settings.getInt("vibrationIntensity", DEFAULT_VIBRATION))
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change < 0 && ::audio.isInitialized) {
                    cancelClap()
                    sequencer.stop()
                    audio.stopRecording()
                    message = "PAUSED / AUDIO FOCUS CHANGED"
                    if (ready) instrument.invalidate()
                }
            }.build()
        try {
            sessionStore = SessionStore(filesDir)
            val legacy = File(filesDir, "tape.wav")
            if (!settings.getBoolean("sessionMigrationComplete", false)) {
                if (legacy.exists()) {
                    val first = sessionStore.migrate(legacy)
                    val firstPreferences = sessionPreferences(first)
                    if (!firstPreferences.contains("tracks")) {
                        if (!firstPreferences.edit().putString("tracks", settings.getString("tracks", "[]"))
                                .putInt("bpm", settings.getInt("bpm", Sequencer.DEFAULT_BPM)).putInt("track", settings.getInt("track", 0)).commit()) {
                            throw IOException("Cannot save migrated project")
                        }
                    }
                }
                if (!settings.edit().putBoolean("sessionMigrationComplete", true).commit()) throw IOException("Cannot save project settings")
            }
            sessions = sessionStore.list()
            if (sessions.isEmpty()) {
                activateSession(openSession(sessionStore.create()))
                sessions = sessionStore.list()
            } else {
                val active = settings.getString("activeSession", sessions[0].id)
                activateSession(openSession(sessions.lastOrNull { it.id == active } ?: sessions[0]))
            }
            instrument = InstrumentView(this)
            instrument.setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
            setContentView(instrument)
            preparePlayback()
            readySecrets = readyMask()
        } catch (e: Exception) {
            val error = TextView(this)
            error.text = getString(R.string.app_name) + " could not open its sample tape.\n" + e.message
            error.setPadding(ERROR_PADDING, ERROR_PADDING * 2, ERROR_PADDING, ERROR_PADDING)
            setContentView(error)
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        resumedAt = SystemClock.uptimeMillis()
        applySystemTheme()
        if (clapClock != null) {
            clapClock = ClapClock(System.nanoTime() - (SystemClock.elapsedRealtimeNanos() - clapBootOrigin))
            audio.setClapClock(clapClock)
        }
        if (::audio.isInitialized) audio.start()
        if (ready) {
            instrument.resume()
            val filter = IntentFilter(DeviceCaptureService.CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(captureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerLegacyCaptureReceiver(filter)
            captureReceiverRegistered = true
            refreshDeviceCapture()
            if (!busy) preparePlayback()
            if (grantedRequest >= 0) instrument.post { continueGranted() }
            batteryTickAt = SystemClock.uptimeMillis(); batterySavedAt = batteryTickAt
            instrument.removeCallbacks(batteryTick)
            instrument.postDelayed(batteryTick, BATTERY_TICK_MS)
        }
    }

    fun powered() = batteries.powered()

    private fun tickBatteries() {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - batteryTickAt
        batteryTickAt = now
        if (batteries.drain(elapsed)) powerLost()
        if (now - batterySavedAt >= BATTERY_SAVE_MS) {
            batterySavedAt = now
            if (batteries.useBatteries) batteries.save()
            saveStats()
        }
        if (batteries.useBatteries && ready) instrument.invalidate()
    }

    private fun powerLost() {
        cancelClap()
        if (mode == Mode.RECORDING) audio.stopRecording()
        sequencer.stop()
        vibrator?.cancel()
        message = "NO POWER / CHECK THE BATTERIES"
        if (ready) instrument.invalidate()
    }

    fun showBack() {
        if (mode == Mode.BACK) return
        if (mode != Mode.RECORDING) { deadMode = mode; deadSelection = selection }
        mode = Mode.BACK
        haptic()
        instrument.invalidate()
    }

    private fun leaveBack() {
        val previous = deadMode
        deadMode = null
        if (previous != null) { mode = previous; selection = deadSelection }
        else { mode = Mode.MENU; selection = MENU_BACK }
        instrument.invalidate()
    }

    fun batteryLid() {
        val now = SystemClock.uptimeMillis()
        if (!batteries.lidOpen) batteries.openLid(now)
        else if (!batteries.closeLid(now)) message = "PUT ALL FOUR BATTERIES BACK FIRST"
        haptic(); instrument.invalidate()
    }

    fun batteryPress(slot: Int) {
        val now = SystemClock.uptimeMillis()
        val wasPowered = powered()
        if (batteries.installed[slot]) batteries.kick(slot, now) else batteries.insert(slot, now)
        if (wasPowered && !powered()) powerLost()
        if (!wasPowered && powered()) { batteryTickAt = now; message = "POWER ON"; startupChime() }
        haptic(); instrument.invalidate()
    }

    fun caseSwitch(index: Int) {
        batteries.toggleSwitch(index)
        if (batteries.switches != BatteryBay.ALL_SWITCHES && !secretUnlocked[SECRET_COLORS]) {
            secretUnlocked[SECRET_COLORS] = true; secretOn[SECRET_COLORS] = true
            storeSecrets()
        }
        haptic(); instrument.invalidate()
    }

    fun findSneaky() {
        secretUnlocked[SECRET_SNEAKY] = true; secretOn[SECRET_SNEAKY] = true
        storeSecrets()
        haptic()
    }

    private fun startupChime() {
        val rate = Tape.RATE
        val pcm = ShortArray(rate * CHIME_MS / 1000)
        val mix = DoubleArray(pcm.size)
        for (n in CHIME_NOTES.indices) {
            val from = rate * CHIME_STARTS[n] / 1000
            val length = rate * CHIME_LENGTHS[n] / 1000
            for (k in 0 until min(length, pcm.size - from)) {
                val t = k.toDouble() / rate
                val vibrato = if (n == CHIME_NOTES.size - 1) 1 + 0.006 * Math.sin(2 * Math.PI * 6 * t) else 1.0
                val phase = (t * CHIME_NOTES[n] * vibrato) % 1.0
                val wave = if (phase < 0.25) 1.0 else -0.33
                val envelope = min(1.0, k / 96.0) * Math.exp(-t * (if (n == CHIME_NOTES.size - 1) 4.5 else 14.0)) * min(1.0, (length - k) / 96.0)
                mix[from + k] += wave * envelope
            }
        }
        for (i in pcm.indices) pcm[i] = Math.round(mix[i].coerceIn(-1.0, 1.0) * CHIME_LEVEL * Short.MAX_VALUE).toShort()
        try {
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track.write(pcm, 0, pcm.size)
            track.play()
            instrument.postDelayed({ try { track.release() } catch (_: Exception) {} }, pcm.size * 1000L / rate + 200)
        } catch (_: Exception) {}
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerLegacyCaptureReceiver(filter: IntentFilter) {
        registerReceiver(captureReceiver, filter, "com.tapedecksampler.app.permission.CAPTURE_EVENTS", null)
    }

    override fun onPause() {
        foreground = false
        if (::batteries.isInitialized) {
            batteries.drain(SystemClock.uptimeMillis() - batteryTickAt)
            batteries.save()
        }
        if (ready) instrument.removeCallbacks(batteryTick)
        statMillis += SystemClock.uptimeMillis() - resumedAt
        saveStats()
        if (captureReceiverRegistered) { unregisterReceiver(captureReceiver); captureReceiverRegistered = false }
        cancelClap()
        val effectDraft = effectSnapshot != null
        val trackSliceDraft = sliceDraft
        rollbackDraft()
        if (effectDraft) returnToEffects()
        if (trackSliceDraft) { mode = Mode.HOME; selection = 0 }
        vibrator?.cancel()
        if (ready) instrument.pause()
        if (::audio.isInitialized) {
            audio.stop()
            audioManager.abandonAudioFocusRequest(focus)
            save()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::audio.isInitialized) audio.stop()
        val current = if (::tape.isInitialized) tape else null
        val prepared = preparedSession
        worker.execute {
            try { current?.close() } catch (_: Exception) {}
            try { if (prepared != null && prepared.tape !== current) prepared.tape.close() } catch (_: Exception) {}
        }
        cancelLive()
        liveWorker.shutdownNow()
        worker.shutdown()
        super.onDestroy()
    }

    private fun sessionPreferences(entry: SessionStore.Entry): SharedPreferences = getSharedPreferences(entry.id, MODE_PRIVATE)

    @Throws(Exception::class)
    private fun openSession(entry: SessionStore.Entry): LoadedSession {
        val fresh = !entry.tape.exists()
        val loaded = LoadedSession(entry, sessionPreferences(entry), Tape(entry.tape))
        try {
            if (fresh) {
                StarterKit.seed(loaded.tape, loaded.sequencer)
                if (!loaded.preferences.edit().putString("tracks", trackData(loaded.sequencer).toString()).commit()) throw IOException("Cannot save new project")
            }
            loaded.track = load(loaded.sequencer, loaded.preferences)
            loaded.regions = readRegions(loaded.preferences, loaded.sequencer, fresh)
            return loaded
        } catch (e: Exception) {
            loaded.tape.close()
            throw e
        }
    }

    private fun activateSession(loaded: LoadedSession) {
        session = loaded.entry
        preferences = loaded.preferences
        tape = loaded.tape
        sequencer = loaded.sequencer
        track = loaded.track
        regions = loaded.regions
        audio = AudioEngine(sequencer, tape, this)
        audio.setClapClock(clapClock)
        settings.edit().putString("activeSession", session.id).apply()
        pageClipboard = null; swapSource = -1; pasteTarget = -1
        imported = null
        importSource = null
        importWave = null
        clearSourcePreview()
        stepTarget = -1
        sliceDraft = false
        effectSnapshot = null
        cancelLive()
        cursor = 0
        fine = false
        resetTimelineZoom()
        waveVersion++
        message = session.name() + " OPEN"
    }

    private fun load(target: Sequencer, source: SharedPreferences): Int {
        target.tempo(source.getInt("bpm", Sequencer.DEFAULT_BPM))
        target.swing(source.getInt("swing", Sequencer.DEFAULT_SWING))
        try {
            val list = JSONArray(source.getString("tracks", "[]"))
            for (i in 0 until min(Sequencer.TRACKS, list.length())) {
                val json = list.getJSONObject(i)
                val t = target.tracks[i]
                t.steps = json.optInt("steps") and STEP_MASK
                t.start = json.optInt("start").coerceIn(0, Tape.LENGTH - MIN_SLICE_FRAMES)
                t.end = max(t.start + MIN_SLICE_FRAMES, min(Tape.LENGTH, json.optInt("end", Sequencer.DEFAULT_SLICE_FRAMES)))
                readEffects(json, t)
                val pads = json.optJSONArray("padEffects")
                if (pads != null) for (p in 0 until min(Sequencer.STEPS, pads.length())) {
                    pads.optJSONObject(p)?.let { readPad(it, target, i, p) }
                }
            }
        } catch (_: Exception) {}
        target.shareTrackEffects()
        try {
            val pages = JSONArray(source.getString("pages", "[]"))
            for (i in 0 until min(Sequencer.PAGE_COUNT, pages.length())) {
                val json = pages.getJSONObject(i)
                target.restoreSelectedPage(i)
                target.page(i).enabled = json.optBoolean("enabled", i == 0)
                target.page(i).color = Sequencer.updatedPageColor(json.optInt("color", target.page(i).color))
                val trackEffects = json.optJSONArray("trackEffects")
                if (trackEffects != null) for (t in 0 until min(Sequencer.TRACKS, trackEffects.length())) {
                    val controls = target.tracks[t]
                    val mute = controls.mute
                    trackEffects.optJSONObject(t)?.let { readEffects(it, controls) }
                    controls.mute = mute
                }
                for (t in 0 until Sequencer.TRACKS) for (pad in 0 until Sequencer.STEPS) target.resetStep(t, pad)
                val patterns = json.getJSONArray("tracks")
                for (t in 0 until min(Sequencer.TRACKS, patterns.length())) {
                    val pattern = patterns.getJSONObject(t)
                    target.tracks[t].steps = pattern.optInt("steps") and STEP_MASK
                    val pads = pattern.optJSONArray("padEffects")
                    if (pads != null) for (pad in 0 until min(Sequencer.STEPS, pads.length())) {
                        pads.optJSONObject(pad)?.let { readPad(it, target, t, pad) }
                    }
                }
            }
        } catch (_: Exception) {}
        target.restoreSelectedPage(source.getInt("page", 0).coerceIn(0, Sequencer.PAGE_COUNT - 1))
        return source.getInt("track", 0).coerceIn(0, Sequencer.TRACKS - 1)
    }

    private fun readPad(json: JSONObject, target: Sequencer, track: Int, pad: Int) {
        val controls = target.editStep(track, pad)
        readEffects(json, controls)
        controls.custom = if (json.has("custom")) groupMask(json, "custom") else controls.differences(target.tracks[track])
    }

    private fun groupMask(json: JSONObject, key: String): Int {
        val mask = json.optInt(key, 0)
        if (json.optInt("fxVersion", 1) >= FX_VERSION) return mask
        val shift = mask and (1 shl LEGACY_SHIFT_GROUP) != 0
        val kept = mask and ((1 shl LEGACY_WET_GROUP) or (1 shl LEGACY_SHIFT_GROUP)).inv()
        return if (shift) kept or (1 shl Sequencer.Effects.SHIFT) else kept
    }

    private fun readEffects(json: JSONObject, controls: Sequencer.Effects) {
        controls.gain = json.optDouble("gain", Sequencer.Effects.DEFAULT_GAIN.toDouble()).coerceIn(0.0, 1.0).toFloat()
        controls.mute = json.optBoolean("mute")
        controls.speed = json.optInt("speed", 100).coerceIn(SampleProcessor.MIN_SPEED, SampleProcessor.MAX_SPEED)
        controls.pitch = (if (json.has("pitchHalf")) json.optInt("pitchHalf", 0) else json.optInt("pitch", 0) * 2).coerceIn(SampleProcessor.MIN_PITCH, SampleProcessor.MAX_PITCH)
        controls.reverb = json.optInt("reverb", 0).coerceIn(0, 100)
        controls.distortion = json.optInt("distortion", 0).coerceIn(0, TrackEffects.DISTORTIONS.size)
        controls.drive = json.optInt("drive", Sequencer.Effects.DEFAULT_DRIVE).coerceIn(0, 100)
        controls.distortionMix = json.optInt("distortionMix", 100).coerceIn(0, 100)
        controls.highPass = json.optInt("highPass", 0).coerceIn(0, MAX_FILTER_HZ)
        controls.lowPass = json.optInt("lowPass", 0).coerceIn(0, MAX_FILTER_HZ)
        controls.delayBeat = json.optInt("delayBeat", Sequencer.Effects.DEFAULT_DELAY_BEAT).coerceIn(0, TrackEffects.DELAY_BEATS.size - 1)
        controls.delayEchoes = json.optInt("delayEchoes", Sequencer.Effects.DEFAULT_DELAY_ECHOES).coerceIn(1, TrackEffects.MAX_ECHOES)
        controls.delayMix = json.optInt("delayMix", 0).coerceIn(0, 100)
        controls.attack = json.optInt("attack", 0).coerceIn(0, MAX_ENVELOPE_MS)
        controls.decay = json.optInt("decay", 0).coerceIn(0, MAX_ENVELOPE_MS)
        controls.sustain = json.optInt("sustain", 100).coerceIn(0, 100)
        controls.release = json.optInt("release", 0).coerceIn(0, MAX_ENVELOPE_MS)
        val groove = json.optInt("swingOverride", 0)
        controls.swingOverride = if (groove == 0) 0 else groove.coerceIn(Sequencer.MIN_SWING, Sequencer.MAX_SWING)
        controls.nudge = json.optInt("nudge", 0).coerceIn(-MAX_NUDGE_MS, MAX_NUDGE_MS)
        controls.autoKey = json.optInt("autoKey", 0).coerceIn(0, PitchCorrector.KEYS.size - 1)
        controls.autoScale = json.optInt("autoScale", 0).coerceIn(0, PitchCorrector.SCALES.size - 1)
        controls.autoStrength = json.optInt("autoStrength", 0).coerceIn(0, 100)
        controls.autoRetune = json.optInt("autoRetune", Sequencer.Effects.DEFAULT_RETUNE_MS).coerceIn(0, MAX_RETUNE_MS)
        controls.autoMix = json.optInt("autoMix", 100).coerceIn(0, 100)
        controls.chance = json.optInt("chance", 100).coerceIn(0, 100)
        controls.every = json.optInt("every", 1).coerceIn(1, MAX_EVERY)
        controls.wobbleMode = json.optInt("wobbleMode", 0).coerceIn(0, WOBBLE_MODES.size - 1)
        controls.wobbleRate = json.optInt("wobbleRate", Sequencer.Effects.DEFAULT_WOBBLE_RATE).coerceIn(MIN_WOBBLE_RATE, MAX_WOBBLE_RATE)
        controls.wobbleDepth = json.optInt("wobbleDepth", 0).coerceIn(0, 100)
        controls.effectPower = groupMask(json, "effectPower") and EFFECT_MASK
        controls.effectPowerSet = groupMask(json, "effectPowerSet") and EFFECT_MASK
        controls.trimStart = json.optInt("trimStart", 0).coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS)
        controls.trimEnd = json.optInt("trimEnd", 0).coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS)
        val chain = json.optJSONArray("chain")
        if (chain == null || chain.length() != Sequencer.Effects.CHAIN_LENGTH) controls.splitGroups()
        if (chain != null) controls.restoreOrder(IntArray(chain.length()) { chain.optInt(it) })
        controls.normalize()
    }

    private fun effectData(controls: Sequencer.Effects): JSONObject {
        val chain = JSONArray()
        for (group in controls.chain) chain.put(group)
        return JSONObject().put("chain", chain).put("chance", controls.chance).put("every", controls.every)
            .put("wobbleMode", controls.wobbleMode).put("wobbleRate", controls.wobbleRate).put("wobbleDepth", controls.wobbleDepth).put("gain", controls.gain.toDouble()).put("mute", controls.mute).put("speed", controls.speed)
            .put("pitchHalf", controls.pitch).put("reverb", controls.reverb).put("distortion", controls.distortion).put("drive", controls.drive).put("distortionMix", controls.distortionMix)
            .put("highPass", controls.highPass).put("lowPass", controls.lowPass).put("delayBeat", controls.delayBeat)
            .put("delayEchoes", controls.delayEchoes).put("delayMix", controls.delayMix)
            .put("attack", controls.attack).put("decay", controls.decay).put("sustain", controls.sustain).put("release", controls.release)
            .put("swingOverride", controls.swingOverride).put("nudge", controls.nudge)
            .put("autoKey", controls.autoKey).put("autoScale", controls.autoScale).put("autoStrength", controls.autoStrength)
            .put("autoRetune", controls.autoRetune).put("autoMix", controls.autoMix)
            .put("effectPower", controls.effectPower).put("effectPowerSet", controls.effectPowerSet)
            .put("trimStart", controls.trimStart).put("trimEnd", controls.trimEnd).put("custom", controls.custom).put("fxVersion", FX_VERSION)
    }

    private fun effectOrNull(controls: Sequencer.Effects?): Any = if (controls == null) JSONObject.NULL else effectData(controls)

    private fun trackData(source: Sequencer): JSONArray {
        val list = JSONArray()
        synchronized(source) {
            for (t in source.tracks) {
                val pads = JSONArray()
                for (controls in t.padEffects) pads.put(effectOrNull(controls))
                list.put(effectData(t).put("steps", t.steps).put("start", t.start).put("end", t.end).put("padEffects", pads))
            }
        }
        return list
    }

    private fun pageData(source: Sequencer): JSONArray {
        val pages = JSONArray()
        synchronized(source) {
            for (i in 0 until Sequencer.PAGE_COUNT) {
                val page = source.page(i)
                val patterns = JSONArray()
                val trackEffects = JSONArray()
                for (t in 0 until Sequencer.TRACKS) {
                    val pads = JSONArray()
                    for (controls in page.padEffects[t]) pads.put(effectOrNull(controls))
                    patterns.put(JSONObject().put("steps", page.steps[t]).put("padEffects", pads))
                    trackEffects.put(effectData(page.trackEffects[t]))
                }
                pages.put(JSONObject().put("enabled", page.enabled).put("color", page.color).put("tracks", patterns).put("trackEffects", trackEffects))
            }
        }
        return pages
    }

    private fun saveStats() {
        settings.edit().putLong("statMillis", usedMillis()).putLong("statPads", statPads).putLong("statJams", statJams).putFloat("statWheelTurns", statWheelTurns.toFloat()).apply()
    }

    fun wheelTurned(radians: Double) { statWheelTurns += abs(radians) / (2 * Math.PI) }

    private fun usedMillis() = statMillis + if (foreground) SystemClock.uptimeMillis() - resumedAt else 0

    fun stats(): Array<Array<String>> {
        val millis = usedMillis()
        val secrets = secretUnlocked.count { it }
        return arrayOf(
            arrayOf("HOURS USED", String.format(Locale.US, "%.1f h", millis / 3_600_000.0)),
            arrayOf("PADS PRESSED", String.format(Locale.US, "%,d", statPads)),
            arrayOf("WHEEL ROTATIONS", String.format(Locale.US, "%,.1f", statWheelTurns)),
            arrayOf("JAMS", String.format(Locale.US, "%,d", statJams)),
            arrayOf("BATTERIES USED", String.format(Locale.US, "%,d", batteries.used)),
            arrayOf("SECRETS FOUND", "$secrets / $SECRETS"))
    }

    fun save() {
        if (!::sequencer.isInitialized) return
        val list = trackData(sequencer)
        preferences.edit().putString("pages", pageData(sequencer).toString()).putInt("page", sequencer.selectedPage()).putString("tracks", list.toString()).putString("regions", regionData(regions).toString()).putInt("track", track).putInt("bpm", sequencer.bpm()).putInt("swing", sequencer.swing()).apply()
        if (ready) { instrument.invalidate(); checkSecrets() }
    }

    private fun regionData(source: SampleRegions): JSONObject {
        val colors = JSONObject()
        val segments = JSONArray()
        for ((key, value) in source.colors()) colors.put(key, value)
        for (region in source.snapshot()) {
            segments.put(JSONObject().put("start", region.start).put("end", region.end).put("id", region.id).put("name", region.name).put("color", region.color))
        }
        return JSONObject().put("colors", colors).put("segments", segments)
    }

    private fun readRegions(source: SharedPreferences, target: Sequencer, fresh: Boolean): SampleRegions {
        val saved = source.getString("regions", null)
        if (saved != null) {
            try {
                val result = SampleRegions()
                val data = JSONObject(saved)
                val colors = data.getJSONObject("colors")
                for (key in colors.keys()) result.restoreColor(key, colors.getInt(key))
                val segments = data.getJSONArray("segments")
                for (i in 0 until segments.length()) {
                    val r = segments.getJSONObject(i)
                    val regionStart = r.getInt("start")
                    val regionEnd = min(Tape.LENGTH, r.getInt("end"))
                    if (regionStart >= regionEnd) continue
                    result.restore(SampleRegions.Region(regionStart, regionEnd, r.getString("id"), r.getString("name"), r.getInt("color")))
                }
                return result
            } catch (_: Exception) {}
        }
        val result = SampleRegions()
        val names = arrayOf("Kick", "Tom", "Snare", "Closed hat", "Open hat", "Square", "Synth", "Bass")
        for (i in target.tracks.indices.reversed()) {
            val t = target.tracks[i]
            result.place(t.start, t.end, (if (fresh) "starter:" else "existing:") + i, if (fresh) names[i] else "Track " + (i + 1) + " sample")
        }
        source.edit().putString("regions", regionData(result).toString()).apply()
        return result
    }

    @JvmField val secretOn = BooleanArray(SECRETS)
    @JvmField val secretUnlocked = BooleanArray(SECRETS)
    private var readySecrets = 0

    private fun secretCondition(secret: Int) = when (secret) {
        SECRET_TRANS -> sequencer.transPageOrder()
        SECRET_PRIDE -> sequencer.allPagesMoved()
        SECRET_SWEDEN -> sequencer.swedenPageOrder()
        SECRET_COLORS -> batteries.switches != BatteryBay.ALL_SWITCHES
        SECRET_SNEAKY -> false
        else -> sessions.any { it.name().trim() == "666" }
    }

    fun secretVisible(secret: Int) = secret != SECRET_COLORS && (secretUnlocked[secret] || ::sequencer.isInitialized && secretCondition(secret))

    private fun secretRow(row: Int): Int {
        var left = row
        for (secret in 0 until SECRETS) if (secretVisible(secret) && left-- == 0) return secret
        return -1
    }

    private fun systemSecrets() = SYSTEM_BATTERIES + if (batteries.found) 1 else 0

    fun systemItems(): Array<String> {
        val items = arrayListOf("Vibration intensity", "Wheel sensitivity", "Dark theme / " + onOff(darkTheme), "Left hand mode / " + onOff(leftHandMode))
        if (batteries.found) items.add("Require batteries / " + onOff(batteries.useBatteries))
        for (secret in 0 until SECRETS) if (secretVisible(secret)) items.add(SECRET_LABELS[secret] + " / " + onOff(secretOn[secret]))
        return items.toTypedArray()
    }

    private fun onOff(on: Boolean) = if (on) "On" else "Off"

    private fun toggleFlag() {
        val secret = secretRow(selection - systemSecrets())
        if (secret < 0) return
        val wasUnlocked = secretUnlocked[secret]
        secretOn[secret] = !secretOn[secret]
        secretUnlocked[secret] = secretUnlocked[secret] || secretOn[secret]
        storeSecrets()
        if (!wasUnlocked && secretUnlocked[secret]) instrument.blinkPad(UNLOCK_BLINKS)
    }

    private fun storeSecrets() {
        val editor = settings.edit()
        for (secret in 0 until SECRETS) editor.putBoolean(SECRET_KEYS[secret], secretOn[secret]).putBoolean(SECRET_KEYS[secret] + "Unlocked", secretUnlocked[secret])
        editor.apply()
        transFlag = secretOn[SECRET_TRANS]; prideFlag = secretOn[SECRET_PRIDE]; transFlagUnlocked = secretUnlocked[SECRET_TRANS]; prideFlagUnlocked = secretUnlocked[SECRET_PRIDE]
    }

    private fun readyMask(): Int {
        if (!::sequencer.isInitialized) return 0
        var mask = 0
        for (secret in 0 until SECRETS) if (!secretUnlocked[secret] && secretCondition(secret)) mask = mask or (1 shl secret)
        return mask
    }

    fun checkSecrets() {
        val ready = readyMask()
        if (ready and readySecrets.inv() != 0 && this.ready) instrument.blinkPad(READY_BLINKS)
        readySecrets = ready
    }

    fun syncItems() = arrayOf("Re-sync", "Turn sync off")

    fun syncStatus() = if (clapClock != null) "CLAP SYNC / " + sequencer.bpm() + " BPM" else "SYNC IS CURRENTLY TURNED OFF"

    fun syncHeader(): String = if (sequencer.isSyncWaiting()) "SYNCING" else screenPath()

    fun setSyncEnabled(enabled: Boolean) {
        cancelClap()
        sequencer.stop()
        syncEnabled = enabled
        clapClock = null
        audio.setClapClock(null)
    }

    fun requestNewSession() {
        if (busy) return
        nameDialog("New tape", SessionStore.nextDefaultName(sessions), "Next") { label ->
            pendingName = label; colorForNew = true; editTarget = null
            mode = Mode.PROJECT_COLOR; selection = CASSETTE_ROWS[0] + sessions.size % (CASSETTE_ROWS[1] + CASSETTE_ROWS[2])
            instrument.invalidate()
        }
    }

    private fun nameDialog(title: String, initial: String, action: String, done: (String) -> Unit) {
        val name = EditText(this)
        name.isSingleLine = true
        name.hint = "Project name"
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        name.filters = arrayOf(InputFilter.LengthFilter(SessionStore.MAX_NAME_LENGTH))
        name.setText(initial)
        name.selectAll()
        name.setPadding(NAME_PADDING_X, NAME_PADDING_Y, NAME_PADDING_X, NAME_PADDING_Y)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(name)
            .setPositiveButton(action, null).setNegativeButton("Cancel", null).create()
        newSessionDialog = dialog
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val label = name.text.toString().trim()
                if (label.isEmpty()) { name.requestFocus(); return@setOnClickListener }
                dialog.dismiss()
                done(label)
            }
            positive.isEnabled = name.text.toString().isNotBlank()
            name.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(value: CharSequence, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(value: Editable) { positive.isEnabled = value.toString().isNotBlank() }
            })
            name.requestFocus()
            name.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(name, InputMethodManager.SHOW_IMPLICIT) }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    @JvmOverloads
    fun createSession(name: String, color: Int = -1) { switchSession(null, name, color, null) }

    fun browseList(): ArrayList<SessionStore.Entry> = browseListFor(mode)

    fun browseListFor(view: Mode): ArrayList<SessionStore.Entry> {
        if (view != Mode.PROJECT_OPEN) return sessions
        return sessions.filterTo(ArrayList()) { it.id != session.id }
    }

    fun switchSession(target: SessionStore.Entry?) {
        if (target == null) { requestNewSession(); return }
        switchSession(target, "", -1, null)
    }

    fun colorIndex(entry: SessionStore.Entry) = cassetteIndex(entry.color, entry.number)

    fun cassetteColor(entry: SessionStore.Entry) = CASSETTE_COLORS[colorIndex(entry)]

    fun carouselMode() = mode == Mode.PROJECT_OPEN || mode == Mode.PROJECT_EDIT || mode == Mode.PROJECT_EDIT_MENU || mode == Mode.PROJECT_COLOR || mode == Mode.PROJECT_DELETE

    private fun browsed(): SessionStore.Entry? {
        val list = browseList()
        return if (list.isEmpty()) null else list[min(selection, list.size - 1)]
    }

    fun carouselName(): String {
        if (mode == Mode.PROJECT_OPEN || mode == Mode.PROJECT_EDIT) return browsed()?.name() ?: ""
        if (mode == Mode.PROJECT_COLOR && colorForNew) return pendingName
        return editTarget?.name() ?: ""
    }

    fun carouselColor(): Int {
        if (mode == Mode.PROJECT_OPEN || mode == Mode.PROJECT_EDIT) return browsed()?.let { cassetteColor(it) } ?: CASSETTE_COLORS[0]
        if (mode == Mode.PROJECT_COLOR) return CASSETTE_COLORS[selection]
        return editTarget?.let { cassetteColor(it) } ?: CASSETTE_COLORS[0]
    }

    fun carouselKey() = if (mode == Mode.PROJECT_OPEN || mode == Mode.PROJECT_EDIT) selection else -1

    private fun sessionIndex(entry: SessionStore.Entry?): Int {
        if (entry == null) return 0
        return max(0, sessions.indexOfFirst { it.id == entry.id })
    }

    private fun refreshSessions() {
        try { sessions = sessionStore.list() } catch (e: Exception) { error(e.message); return }
        for (entry in sessions) {
            if (entry.id == session.id) session = entry
            if (entry.id == editTarget?.id) editTarget = entry
        }
    }

    private fun openProject(target: SessionStore.Entry) {
        if (target.id == session.id) { mode = Mode.HOME; message = "TAPE ALREADY LOADED"; return }
        instrument.insertCassette(target.name(), cassetteColor(target), session.name(), cassetteColor(session))
        switchSession(target, "", -1, null)
    }

    private fun renameProject() {
        val target = editTarget ?: return
        nameDialog("Rename tape", target.name(), "Rename") { label ->
            work("RENAMING TAPE", { sessionStore.rename(target, label) }) {
                refreshSessions()
                checkSecrets()
                message = "RENAMED $label"
            }
        }
    }

    private fun recolorProject(color: Int) {
        val target = editTarget ?: return
        work("RECOLORING TAPE", { sessionStore.recolor(target, CASSETTE_NAMES[color]) }) {
            refreshSessions()
            mode = Mode.PROJECT_EDIT_MENU; selection = 1
        }
    }

    private fun deleteProject() {
        val target = editTarget ?: return
        if (sessions.size < 2) { error("Keep at least one tape"); mode = Mode.PROJECT_EDIT_MENU; selection = 2; return }
        val remove = Runnable {
            var failure: String? = null
            try { sessionStore.delete(target); deleteSharedPreferences(target.id) }
            catch (e: Exception) { failure = e.message }
            val shown = failure
            runOnUiThread {
                if (shown != null) error(shown)
                refreshSessions()
                if (mode == Mode.PROJECT_EDIT) selection = min(selection, sessions.size - 1)
                instrument.invalidate()
            }
        }
        editTarget = null
        if (target.id == session.id) {
            val next = sessions[if (sessionIndex(target) == 0) 1 else 0]
            instrument.insertCassette(next.name(), cassetteColor(next), target.name(), cassetteColor(target))
            switchSession(next, "", -1, remove)
        } else {
            mode = Mode.PROJECT_EDIT; selection = 0
            message = "DELETED " + target.name()
            worker.execute(remove)
        }
    }

    private fun switchSession(target: SessionStore.Entry?, name: String, color: Int, afterClose: Runnable?) {
        if (busy || mode == Mode.RECORDING || (target != null && target.id == session.id)) {
            if (!busy && mode != Mode.RECORDING && target != null) mode = Mode.HOME
            return
        }
        save()
        audio.stop()
        work(if (target == null) "CREATING TAPE" else "LOADING " + target.name(), {
            preparedSession = openSession(target ?: sessionStore.create(name, if (color < 0) "" else CASSETTE_NAMES[color]))
        }) {
            val previous = tape
            activateSession(preparedSession!!)
            preparedSession = null
            try { sessions = sessionStore.list() } catch (e: Exception) { error(e.message) }
            mode = Mode.HOME
            selection = 0
            checkSecrets()
            if (foreground) audio.start()
            preparePlayback()
            worker.execute {
                try { previous.close() } catch (e: Exception) { runOnUiThread { error("Could not close previous project tape") } }
                afterClose?.run()
            }
        }
    }

    fun selectTrack(index: Int) { selectTrack(index, true) }

    fun toggleTrackMute(index: Int) {
        if (busy || index < 0 || index >= sequencer.tracks.size) return
        val muted: Boolean
        synchronized(sequencer) {
            val controls = sequencer.tracks[index]
            controls.mute = !controls.mute
            muted = controls.mute
            if (index == track) {
                if (stepTarget < 0) effectSnapshot?.mute = muted
                if (sliceDraft) sliceSnapshot?.mute = muted
            }
        }
        try {
            val stored = preferences.getString("tracks", null)
            val data = if (stored == null) trackData(sequencer) else JSONArray(stored)
            data.getJSONObject(index).put("mute", muted)
            preferences.edit().putString("tracks", data.toString()).apply()
        } catch (e: Exception) { error("Could not save track mute: " + e.message) }
        message = "TRACK " + (index + 1) + if (muted) " MUTED" else " UNMUTED"
        haptic(); instrument.invalidate()
    }

    private fun selectTrack(index: Int, audition: Boolean) {
        if (busy || (mode != Mode.HOME && mode != Mode.MENU && mode != Mode.EFFECTS)) return
        if (track == index && !audition) return
        val targetChanged = track != index || stepTarget >= 0
        if (track != index) { track = index; save() }
        stepTarget = -1
        if (mode == Mode.EFFECTS && targetChanged) { effectGroup = -1; selection = 0; deletedEffects.clear() }
        haptic()
        message = "TRACK " + (track + 1) + " SELECTED"
        if (audition) previewTrack()
    }

    fun pad(index: Int) {
        if (busy || carouselMode()) return
        statPads++
        when (mode) {
            Mode.PAGE_PASTE, Mode.PAGE_RESET -> {
                haptic()
                if (index != pasteTarget) pasteTarget = index
                else if (mode == Mode.PAGE_PASTE) applyPaste() else applyReset()
                instrument.invalidate()
                return
            }
            Mode.PAGE_COPIED -> { haptic(); pasteTarget = index; mode = Mode.PAGE_PASTE; instrument.invalidate(); return }
            Mode.PAGES -> { haptic(); pagePad(index); instrument.invalidate(); return }
            Mode.RECORDING, Mode.DEVICE_RECORD, Mode.SYNC_CLAP -> return
            else -> {}
        }
        haptic()
        synchronized(sequencer) { sequencer.tracks[track].steps = sequencer.tracks[track].steps xor (1 shl index) }
        message = "STEP " + (index + 1) + if (sequencer.tracks[track].steps and (1 shl index) != 0) " ON" else " OFF"
        save()
    }

    fun previewTrack() {
        if (busy || mode == Mode.RECORDING || mode == Mode.DEVICE_RECORD || mode == Mode.SYNC_CLAP) return
        haptic()
        if (acquireFocus()) sequencer.preview(track)
    }

    private fun acquireFocus(): Boolean {
        if (audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audio.start()
            return true
        }
        error("Audio is in use by another app")
        return false
    }

    fun transport() {
        if (busy || mode == Mode.RECORDING || mode == Mode.DEVICE_RECORD || mode == Mode.SYNC_CLAP) return
        if (sequencer.isPlaying() || sequencer.isSyncWaiting()) {
            sequencer.stop()
            message = "STOPPED / NEXT PLAY STARTS AT 01"
        } else if (acquireFocus()) {
            if (syncEnabled && clapClock == null) beginClap()
            else if (syncEnabled) { sequencer.playSynced(); message = "SYNCING" }
            else { sequencer.play(); message = "PLAYING / ${Sequencer.STEPS} STEPS · ${Sequencer.TRACKS} TRACKS" }
        }
        haptic()
        instrument.invalidate()
    }

    fun center() { confirm() }

    fun wheelMenu() {
        if (mode == Mode.HOME) confirm() else cancel()
        instrument.invalidate()
    }

    fun wheelPlay() {
        if (busy) return
        when {
            mode == Mode.DEVICE_RECORD -> if (DeviceCaptureService.active()) DeviceCaptureService.stop(this)
            mode == Mode.RECORDING -> { audio.stopRecording(); haptic() }
            isTimelineMode() -> previewTimeline()
            else -> transport()
        }
    }

    private fun previewTimeline() {
        haptic()
        if (mode == Mode.IMPORT_START || mode == Mode.IMPORT_END) {
            val ready = sourcePreview
            if (previewSource === importSource && previewStart == importStart && previewEnd == importEnd && ready != null) {
                if (acquireFocus()) sequencer.toggleSelection(ready, 0, ready.size)
            } else {
                sequencer.stop()
                val source = importSource
                val from = importStart
                val to = importEnd
                work("PREPARING SELECTION PREVIEW", { sourcePreview = SampleDecoder.decode(this, source, from, to) }) {
                    previewSource = source
                    previewStart = from
                    previewEnd = to
                    val decoded = sourcePreview!!
                    if (acquireFocus()) sequencer.toggleSelection(decoded, 0, decoded.size)
                }
            }
        } else if (acquireFocus()) {
            when (mode) {
                Mode.IMPORT_POSITION -> imported?.let { sequencer.toggleSelection(it, 0, it.size) }
                Mode.RECORD_POSITION -> sequencer.toggleSelection(tape.samples, cursor, Tape.LENGTH)
                else -> sequencer.toggleSelection(tape.samples, start, end)
            }
        }
        instrument.invalidate()
    }

    private fun clearSourcePreview() {
        previewSource = null
        sourcePreview = null
    }

    fun wheelSide(delta: Int) {
        if (busy || delta == 0) return
        if (effectStackPage()) {
            if (delta < 0) deleteEffect() else undoEffect()
            haptic(); instrument.invalidate()
            return
        }
        if (mode == Mode.FX_REORDER && effectHeld >= 0) { wheel(delta); instrument.invalidate(); return }
        if (mode == Mode.HOME) openSlice()
        if (mode == Mode.START || mode == Mode.END) {
            mode = if (delta < 0) Mode.START else Mode.END
            cursor = if (delta < 0) start else end
            fitTimelineClip(start.toLong(), end.toLong())
        } else if (mode == Mode.IMPORT_START || mode == Mode.IMPORT_END) {
            mode = if (delta < 0) Mode.IMPORT_START else Mode.IMPORT_END
            fitTimelineClip(importStart, importEnd)
        }
        haptic()
        instrument.invalidate()
    }

    private fun moveSelection(delta: Int, count: Int) = (selection + delta.toLong()).coerceIn(0L, count - 1L).toInt()

    fun effectControls(): Sequencer.Effects = sequencer.controls(track, stepTarget) ?: sequencer.tracks[track]

    fun effectStackPage() = mode == Mode.EFFECTS && effectGroup < 0
    fun canUndoEffect() = deletedEffects.isNotEmpty()
    fun effectSideKeys() = effectStackPage() || mode == Mode.FX_REORDER && effectHeld >= 0

    fun effectStack(): IntArray {
        val stack = effectControls().stack()
        if (stepTarget < 0) return stack
        val inherited = sequencer.controls(track, stepTarget) == null
        return stack.filter { !trackOnly(it) && !(inherited && it == Sequencer.Effects.VOLUME) }.toIntArray()
    }

    fun canReorder(group: Int): Boolean {
        if (!Sequencer.Effects.movable(group)) return false
        return effectStack().count { Sequencer.Effects.movable(it) } > 1
    }

    private fun stackRow(group: Int) = max(0, effectStack().indexOf(group))

    fun addGroups(): IntArray {
        val stack = effectStack()
        return FX_ADD_ORDER.filter { (stepTarget < 0 || !trackOnly(it)) && it !in stack }.toIntArray()
    }

    fun addItems(): Array<String> {
        val items = addGroups().map { FX_NAMES[it] }.toMutableList()
        if (stepTarget >= 0) {
            items.add(if (effectControls().mute) "Unmute" else "Mute")
            items.add("Preview sample")
            items.add("Reset pad effects")
        }
        return items.toTypedArray()
    }

    fun effectItems(): Array<String> {
        if (effectGroup >= 0) {
            val parameters = FX_GROUP_PARAMETERS[effectGroup]
            val items = parameters.indices.map { FX_GROUP_LABELS[effectGroup][it] + " / " + effectDisplay(parameters[it], effectSetting(parameters[it])) }.toMutableList()
            if (parameters.size > 1) items.add("Confirm")
            return items.toTypedArray()
        }
        return (effectStack().map { FX_NAMES[it] } + "Add effect").toTypedArray()
    }

    private fun addEffect(group: Int) {
        val transform = transforms(group)
        if (transform) cancelLive()
        synchronized(sequencer) {
            val controls = editableEffects()
            controls.power(group, true)
            if (Sequencer.Effects.movable(group)) controls.place(group, Int.MAX_VALUE)
            markPad(group, Sequencer.Effects.movable(group))
            inheritTrack()
            if (transform) sequencer.refresh(track, stepTarget)
        }
        deletedEffects.removeAll { it.group == group }
        mode = Mode.EFFECTS; effectGroup = group; selection = 0
        if (transform) scheduleLive()
        save()
        if (FX_GROUP_PARAMETERS[group].size == 1) { effectDirect = true; openEffect() }
    }

    private fun deleteEffect() {
        val stack = effectStack()
        if (selection >= stack.size) return
        val group = stack[selection]
        instrument.effectDeleting(selection)
        val transform = transforms(group)
        if (transform) cancelLive()
        synchronized(sequencer) {
            val controls = editableEffects()
            deletedEffects.add(DeletedEffect(group, controls.position(group), controls.copy()))
            controls.resetGroup(group)
            markPad(group, false)
            inheritTrack()
            if (transform) sequencer.refresh(track, stepTarget)
        }
        selection = min(selection, effectStack().size)
        if (transform) scheduleLive()
        save()
    }

    private fun undoEffect() {
        if (deletedEffects.isEmpty()) return
        instrument.cancelEffectDeleting()
        val deleted = deletedEffects.removeAt(deletedEffects.size - 1)
        val transform = transforms(deleted.group)
        if (transform) cancelLive()
        synchronized(sequencer) {
            val controls = editableEffects()
            controls.restoreGroup(deleted.group, deleted.before)
            if (Sequencer.Effects.movable(deleted.group)) controls.place(deleted.group, deleted.position)
            markPad(deleted.group, Sequencer.Effects.movable(deleted.group))
            inheritTrack()
            if (transform) sequencer.refresh(track, stepTarget)
        }
        selection = stackRow(deleted.group)
        if (transform) scheduleLive()
        save()
    }

    private fun moveHeldEffect(delta: Int) {
        synchronized(sequencer) { editableEffects().move(effectHeld, delta); markPad(-1, true); inheritTrack() }
        selection = stackRow(effectHeld)
    }

    private fun confirmReorder() {
        if (effectHeld >= 0) { effectHeld = -1; save(); return }
        val stack = effectStack()
        if (selection >= stack.size) return
        if (canReorder(stack[selection])) effectHeld = stack[selection]
        else instrument.effectLocked(stack[selection])
    }

    fun effectDisplay(parameter: Int, value: Int): String = when {
        parameter == FX_PITCH -> halfSteps(value) + " st"
        parameter == FX_DISTORTION -> TrackEffects.DISTORTIONS[(value - 1).coerceIn(0, TrackEffects.DISTORTIONS.size - 1)]
        parameter == FX_DELAY_TIME -> Math.round(TrackEffects.delayFrames(value, sequencer.bpm()) * 1000.0 / Tape.RATE).toString() + " ms"
        parameter == FX_DELAY_FEEDBACK -> if (value == 1) "1 echo" else "$value echoes"
        parameter == FX_HIGH_PASS || parameter == FX_LOW_PASS -> if (value == 0) "Off" else "$value Hz"
        parameter == FX_ATTACK || parameter == FX_DECAY || parameter == FX_RELEASE || parameter == FX_AUTO_RETUNE -> "$value ms"
        parameter == FX_AUTO_KEY -> PitchCorrector.KEYS[value]
        parameter == FX_AUTO_SCALE -> PitchCorrector.SCALES[value]
        parameter == FX_AUTO_STRENGTH && value == 0 -> "Off"
        parameter == FX_SWING && value == 0 -> sequencer.swing().toString() + "%"
        parameter == FX_NUDGE || parameter == FX_TRIM_START || parameter == FX_TRIM_END -> signed(value) + " ms"
        parameter == FX_EVERY -> ordinal(value)
        parameter == FX_WOBBLE_MODE -> WOBBLE_MODES[value]
        parameter == FX_WOBBLE_RATE -> String.format(Locale.US, "%.1f Hz", value / WOBBLE_RATE_PER_HZ)
        else -> "$value%"
    }

    fun effectHint(parameter: Int): String = when (parameter) {
        FX_SPEED -> "${SampleProcessor.MIN_SPEED}-${SampleProcessor.MAX_SPEED}% / PITCH PRESERVED"
        FX_PITCH -> "${SampleProcessor.MIN_PITCH / 2} TO +${SampleProcessor.MAX_PITCH / 2} SEMITONES / 0.5 STEPS"
        FX_HIGH_PASS, FX_LOW_PASS -> "OFF / $MIN_FILTER_HZ-$MAX_FILTER_HZ Hz / LIVE"
        FX_DELAY_TIME -> (if (effectValue == TrackEffects.DELAY_BEATS.size - 1) "1 BAR" else TrackEffects.DELAY_BEATS[effectValue].replace("T", " TRIPLET").replace("D", " DOTTED") + " NOTE") + " AT " + sequencer.bpm() + " BPM"
        FX_DELAY_FEEDBACK -> "1-${TrackEffects.MAX_ECHOES} REPEATS / FADING OUT"
        FX_ATTACK, FX_DECAY -> "0-$MAX_ENVELOPE_MS ms / LIVE"
        FX_RELEASE -> "FADE AT SAMPLE END / 0-$MAX_ENVELOPE_MS ms"
        FX_AUTO_KEY, FX_AUTO_SCALE -> "MONOPHONIC VOCALS / INSTRUMENTS"
        FX_AUTO_STRENGTH -> "0% OFF / 100% NOTE CORRECTION"
        FX_AUTO_RETUNE -> "0 ms ROBOTIC / $MAX_RETUNE_MS ms GENTLE"
        FX_AUTO_MIX -> "0% ORIGINAL / 100% CORRECTED"
        FX_SWING -> Sequencer.MIN_SWING.toString() + "-" + Sequencer.MAX_SWING + "% / RESET USES GLOBAL"
        FX_NUDGE, FX_TRIM_START, FX_TRIM_END -> "NEGATIVE EARLIER / POSITIVE LATER"
        FX_CHANCE -> "0-100% ODDS PER HIT"
        FX_EVERY -> "PLAYS EVERY NTH LOOP PASS"
        FX_WOBBLE_MODE -> "VOLUME / PITCH / SPEED"
        FX_WOBBLE_RATE -> "${MIN_WOBBLE_RATE / WOBBLE_RATE_PER_HZ}-${(MAX_WOBBLE_RATE / WOBBLE_RATE_PER_HZ).toInt()} Hz / LIVE"
        FX_WOBBLE_DEPTH -> "0-100% / LIVE"
        else -> "LIVE / SELECT TO SAVE"
    }

    private fun effectSetting(parameter: Int): Int {
        val controls = effectControls()
        return when (parameter) {
            FX_SPEED -> controls.speed
            FX_PITCH -> controls.pitch
            FX_REVERB -> controls.reverb
            FX_DISTORTION -> controls.distortion
            FX_DISTORTION_MIX -> controls.distortionMix
            FX_VOLUME -> Math.round((if (stepTarget >= 0 && sequencer.controls(track, stepTarget) == null) 1f else controls.gain) * 100)
            FX_HIGH_PASS -> controls.highPass
            FX_LOW_PASS -> controls.lowPass
            FX_DELAY_TIME -> controls.delayBeat
            FX_DELAY_FEEDBACK -> controls.delayEchoes
            FX_DELAY_MIX -> controls.delayMix
            FX_ATTACK -> controls.attack
            FX_DECAY -> controls.decay
            FX_SUSTAIN -> controls.sustain
            FX_RELEASE -> controls.release
            FX_AUTO_KEY -> controls.autoKey
            FX_AUTO_SCALE -> controls.autoScale
            FX_AUTO_STRENGTH -> controls.autoStrength
            FX_AUTO_RETUNE -> controls.autoRetune
            FX_AUTO_MIX -> controls.autoMix
            FX_SWING -> if (controls.swingOverride == 0) sequencer.swing() else controls.swingOverride
            FX_NUDGE -> controls.nudge
            FX_CHANCE -> controls.chance
            FX_EVERY -> controls.every
            FX_WOBBLE_MODE -> controls.wobbleMode
            FX_WOBBLE_RATE -> controls.wobbleRate
            FX_WOBBLE_DEPTH -> controls.wobbleDepth
            FX_TRIM_START -> controls.trimStart
            FX_TRIM_END -> controls.trimEnd
            else -> throw IllegalArgumentException("Unknown effect")
        }
    }

    private fun editableEffects(): Sequencer.Effects = if (stepTarget < 0) sequencer.tracks[track] else sequencer.editStep(track, stepTarget)

    private fun markPad(group: Int, order: Boolean) {
        if (stepTarget < 0) return
        val controls = sequencer.editStep(track, stepTarget)
        if (group >= 0) controls.custom = controls.custom or (1 shl group)
        if (order) controls.custom = controls.custom or Sequencer.Effects.CHAIN_BIT
    }

    private fun inheritTrack() { if (stepTarget < 0) sequencer.inheritTrack(track) }

    private fun rendersSample(parameter: Int) = parameter == FX_SPEED || parameter == FX_PITCH || parameter in FX_AUTO_KEY..FX_AUTO_MIX || parameter == FX_TRIM_START || parameter == FX_TRIM_END

    private fun liveEffect(parameter: Int, value: Int) {
        synchronized(sequencer) {
            val controls = editableEffects()
            when (parameter) {
                FX_SPEED -> controls.speed = value
                FX_PITCH -> controls.pitch = value
                FX_REVERB -> controls.reverb = value
                FX_DISTORTION -> controls.distortion = value
                FX_DISTORTION_MIX -> controls.distortionMix = value
                FX_VOLUME -> controls.gain = value / 100f
                FX_HIGH_PASS -> controls.highPass = value
                FX_LOW_PASS -> controls.lowPass = value
                FX_DELAY_TIME -> controls.delayBeat = value
                FX_DELAY_FEEDBACK -> controls.delayEchoes = value
                FX_DELAY_MIX -> controls.delayMix = value
                FX_ATTACK -> controls.attack = value
                FX_DECAY -> controls.decay = value
                FX_SUSTAIN -> controls.sustain = value
                FX_RELEASE -> controls.release = value
                FX_AUTO_KEY -> controls.autoKey = value
                FX_AUTO_SCALE -> controls.autoScale = value
                FX_AUTO_STRENGTH -> controls.autoStrength = value
                FX_AUTO_RETUNE -> controls.autoRetune = value
                FX_AUTO_MIX -> controls.autoMix = value
                FX_SWING -> controls.swingOverride = value
                FX_NUDGE -> controls.nudge = value
                FX_CHANCE -> controls.chance = value
                FX_EVERY -> controls.every = value
                FX_WOBBLE_MODE -> controls.wobbleMode = value
                FX_WOBBLE_RATE -> controls.wobbleRate = value
                FX_WOBBLE_DEPTH -> controls.wobbleDepth = value
                FX_TRIM_START -> controls.trimStart = value
                FX_TRIM_END -> controls.trimEnd = value
            }
            markPad(FX_GROUP_PARAMETERS.indexOfFirst { parameter in it }, false)
            inheritTrack()
            if (rendersSample(parameter)) sequencer.refresh(track, stepTarget)
        }
        if (rendersSample(parameter)) scheduleLive()
    }

    fun holdPad(step: Int) {
        if (stepTarget == step && editingStep(step)) applyStepEffects()
        else openStepEffects(step)
    }

    private fun applyStepEffects() {
        if (busy) return
        val step = stepTarget
        if (mode == Mode.FX_VALUE) commitEffect()
        cancelLive()
        effectHeld = -1; effectGroup = -1; effectDirect = false
        deletedEffects.clear()
        stepTarget = -1
        mode = if (stepFromHold) Mode.HOME else Mode.MENU; selection = 0
        message = String.format(Locale.US, "PAD %02d EFFECTS APPLIED", step + 1)
        save(); preparePlayback()
        haptic(); instrument.invalidate()
    }

    fun openStepEffects(step: Int) {
        if (pageMenu() || carouselMode()) return
        if (busy || mode == Mode.RECORDING || mode == Mode.DEVICE_RECORD || mode == Mode.SYNC_CLAP) return
        rollbackDraft()
        sequencer.stopSelectionPreview()
        stepTarget = step; stepFromHold = true
        effectGroup = -1; effectHeld = -1
        effectDirect = false
        deletedEffects.clear()
        mode = Mode.EFFECTS; selection = 0
        haptic(); instrument.invalidate()
    }

    private fun openAddedEffect() {
        val groups = addGroups()
        if (selection < groups.size) { addEffect(groups[selection]); return }
        val row = selection - groups.size
        val pad = stepTarget >= 0
        if (pad && row == 0) {
            synchronized(sequencer) { val controls = editableEffects(); controls.mute = !controls.mute }
            save(); return
        }
        if (pad && row == 1) { if (acquireFocus()) sequencer.previewStep(track, stepTarget); return }
        cancelLive()
        sequencer.resetStep(track, stepTarget)
        deletedEffects.clear()
        mode = Mode.EFFECTS; selection = 0
        save()
    }

    private fun openEffect() {
        if (effectGroup < 0) {
            val stack = effectStack()
            if (selection >= stack.size) {
                if (addItems().isNotEmpty()) { mode = Mode.FX_ADD; selection = 0 }
                return
            }
            effectGroup = stack[selection]; selection = 0
            if (FX_GROUP_PARAMETERS[effectGroup].size == 1) { effectDirect = true; openEffect() }
            return
        }
        if (selection >= FX_GROUP_PARAMETERS[effectGroup].size) {
            selection = stackRow(effectGroup); effectGroup = -1
            return
        }
        effectParameter = FX_GROUP_PARAMETERS[effectGroup][selection]
        effectReturnSelection = selection
        effectValue = effectSetting(effectParameter)
        effectHadOverride = stepTarget < 0 || sequencer.controls(track, stepTarget) != null
        effectSnapshot = effectControls().copy()
        mode = Mode.FX_VALUE
    }

    private fun commitEffect() {
        effectSnapshot = null
        cancelLive()
        returnToEffects()
        save(); preparePlayback()
    }

    private fun returnToEffects() {
        mode = Mode.EFFECTS; selection = effectReturnSelection
        if (effectDirect) { effectDirect = false; selection = stackRow(effectGroup); effectGroup = -1 }
    }

    private fun cancelLive() {
        liveGeneration++
        liveTask?.cancel(true)
        liveTask = null
        if (ready) instrument.removeCallbacks(liveUpdate)
    }

    private fun scheduleLive() {
        cancelLive()
        instrument.postDelayed(liveUpdate, LIVE_UPDATE_MS)
    }

    private fun processLive() {
        val generation = liveGeneration
        val index = track
        val target = sequencer
        val speeds = IntArray(Sequencer.STEPS + 1)
        val pitches = IntArray(Sequencer.STEPS + 1)
        synchronized(target) {
            for (step in -1 until Sequencer.STEPS) {
                val controls = target.controls(index, step) ?: continue
                speeds[step + 1] = controls.speed; pitches[step + 1] = controls.pitch
            }
        }
        liveTask = liveWorker.submit {
            try {
                val results = arrayOfNulls<Sequencer.PreparedSample>(Sequencer.STEPS + 1)
                for (step in -1 until Sequencer.STEPS) {
                    if (generation != liveGeneration) return@submit
                    if (speeds[step + 1] > 0 && target.needsPreparation(index, step)) results[step + 1] = target.prepare(index, step, speeds[step + 1], pitches[step + 1])
                }
                runOnUiThread {
                    if (generation != liveGeneration || target !== sequencer || !foreground) return@runOnUiThread
                    try {
                        for (step in -1 until Sequencer.STEPS) results[step + 1]?.let { target.apply(index, step, it) }
                    } catch (stale: IllegalStateException) { if (!busy && mode != Mode.RECORDING) scheduleLive() }
                    instrument.invalidate()
                }
            } catch (e: Exception) { runOnUiThread { if (generation == liveGeneration) error("Could not update effects: " + e.message) } }
        }
    }

    private fun openSlice() {
        val controls = sequencer.tracks[track]
        start = controls.start; sliceBeforeStart = start
        end = controls.end; sliceBeforeEnd = end
        sliceSnapshot = controls.copy(); sliceDraft = true
        cursor = start; mode = Mode.START
    }

    private fun saveSlice() {
        sequencer.setSlice(track, start, end)
        sliceDraft = false; cancelLive()
        mode = Mode.HOME
        message = "TRACK " + (track + 1) + " SLICE SAVED"
        save()
        preparePlayback()
    }

    private fun updateSliceLive() {
        if (sliceDraft) { sequencer.setSlice(track, start, end); scheduleLive() }
    }

    private fun rollbackDraft() {
        cancelLive()
        if (!::sequencer.isInitialized) return
        val snapshot = effectSnapshot
        if (snapshot != null) {
            synchronized(sequencer) {
                if (stepTarget >= 0 && !effectHadOverride) sequencer.resetStep(track, stepTarget)
                else editableEffects().copyFrom(snapshot)
                inheritTrack()
                sequencer.refresh(track)
            }
            effectSnapshot = null
        }
        if (sliceDraft) {
            synchronized(sequencer) {
                sequencer.tracks[track].copyFrom(sliceSnapshot!!)
                sequencer.setSlice(track, sliceBeforeStart, sliceBeforeEnd)
            }
            start = sliceBeforeStart; end = sliceBeforeEnd; cursor = start
            sliceDraft = false
        }
    }

    private fun preparePlayback() {
        if (::sequencer.isInitialized && sequencer.needsPreparation()) {
            val target = sequencer
            work("PREPARING TRACK EFFECTS", { target.prepareAll() }) {}
        }
    }

    fun wheel(delta: Int) {
        if (busy || delta == 0 || mode == Mode.RECORDING) return
        val previousMode = mode
        val previousValue = wheelValue()
        val increment = timelineIncrement()
        val step = delta * increment
        when (mode) {
            Mode.HOME -> selectTrack((track + delta.toLong()).coerceIn(0L, Sequencer.TRACKS - 1L).toInt(), false)
            Mode.PAGES -> { selection = moveSelection(delta, PAGE_ACTIONS.size); pageAction = selection; swapSource = -1; pagePrompt = false }
            Mode.TEMPO -> selection = moveSelection(delta, 2)
            Mode.SWING -> sequencer.swing(sequencer.swing() + delta)
            Mode.BPM -> sequencer.tempo(sequencer.bpm() + delta)
            Mode.MENU -> selection = moveSelection(delta, MENU.size)
            Mode.ADD_SAMPLE -> selection = moveSelection(delta, ADD_SAMPLE.size)
            Mode.SYSTEM -> selection = moveSelection(delta, systemItems().size)
            Mode.EXPORT -> selection = moveSelection(delta, EXPORT_ITEMS.size)
            Mode.EXPORT_LOOPS -> selection = moveSelection(delta, 2)
            Mode.SYNC -> selection = moveSelection(delta, syncItems().size)
            Mode.SENSITIVITY -> wheelSensitivity = (wheelSensitivity + delta * FAST_STEP.toLong()).coerceIn(MIN_WHEEL.toLong(), MAX_WHEEL.toLong()).toInt()
            Mode.VIBRATION -> setVibrationIntensity((vibrationIntensity + delta * FAST_STEP.toLong()).coerceIn(0L, 100L).toInt())
            Mode.IMPORT_START -> {
                val frames = importSource!!.frames
                val length = importEnd - importStart
                importStart = max(0L, min(frames - MIN_SLICE_FRAMES, importStart + step))
                importEnd = min(frames, importStart + length)
            }
            Mode.IMPORT_END -> importEnd = max(importStart + MIN_SLICE_FRAMES, min(min(importSource!!.frames, importStart + Tape.LENGTH), importEnd + step))
            Mode.EFFECTS -> selection = moveSelection(delta, effectItems().size)
            Mode.FX_ADD -> selection = moveSelection(delta, addItems().size)
            Mode.FX_REORDER -> if (effectHeld >= 0) moveHeldEffect(delta) else selection = moveSelection(delta, effectStack().size)
            Mode.FX_VALUE -> {
                val v = effectValue
                effectValue = when (effectParameter) {
                    FX_SPEED -> (v + delta).coerceIn(SampleProcessor.MIN_SPEED, SampleProcessor.MAX_SPEED)
                    FX_PITCH -> (v + delta).coerceIn(SampleProcessor.MIN_PITCH, SampleProcessor.MAX_PITCH)
                    FX_DISTORTION -> (v + delta.toLong()).coerceIn(1L, TrackEffects.DISTORTIONS.size.toLong()).toInt()
                    FX_HIGH_PASS, FX_LOW_PASS -> {
                        val range = MAX_FILTER_HZ / MIN_FILTER_HZ.toDouble()
                        var position = if (v == 0) 0 else 1 + Math.round(Math.log(v / MIN_FILTER_HZ.toDouble()) / Math.log(range) * FILTER_STEPS).toInt()
                        position = (position + delta).coerceIn(0, FILTER_STEPS + 1)
                        if (position == 0) 0 else Math.round(MIN_FILTER_HZ * Math.pow(range, (position - 1) / FILTER_STEPS.toDouble())).toInt()
                    }
                    FX_DELAY_TIME -> (v + delta).coerceIn(0, TrackEffects.DELAY_BEATS.size - 1)
                    FX_DELAY_FEEDBACK -> (v + delta).coerceIn(1, TrackEffects.MAX_ECHOES)
                    FX_ATTACK, FX_DECAY, FX_RELEASE -> envelopeStep(v, delta)
                    FX_EVERY -> (v + delta).coerceIn(1, MAX_EVERY)
                    FX_WOBBLE_MODE -> (v + delta).coerceIn(0, WOBBLE_MODES.size - 1)
                    FX_WOBBLE_RATE -> (v + delta * FAST_STEP).coerceIn(MIN_WOBBLE_RATE, MAX_WOBBLE_RATE)
                    FX_TRIM_START, FX_TRIM_END -> (v + delta * FAST_STEP).coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS)
                    FX_AUTO_KEY -> (v + delta).coerceIn(0, PitchCorrector.KEYS.size - 1)
                    FX_AUTO_SCALE -> (v + delta).coerceIn(0, PitchCorrector.SCALES.size - 1)
                    FX_AUTO_RETUNE -> (v + delta * FAST_STEP).coerceIn(0, MAX_RETUNE_MS)
                    FX_SWING -> (v + delta).coerceIn(Sequencer.MIN_SWING, Sequencer.MAX_SWING)
                    FX_NUDGE -> (v + delta).coerceIn(-MAX_NUDGE_MS, MAX_NUDGE_MS)
                    else -> (v + delta).coerceIn(0, 100)
                }
                liveEffect(effectParameter, effectValue)
            }
            Mode.PROJECTS -> selection = moveSelection(delta, PROJECT_ITEMS.size)
            Mode.PROJECT_OPEN, Mode.PROJECT_EDIT -> selection = moveSelection(delta, max(1, browseList().size))
            Mode.PROJECT_EDIT_MENU -> selection = moveSelection(delta, PROJECT_EDIT_ITEMS.size)
            Mode.PROJECT_COLOR -> selection = moveSelection(delta, CASSETTE_COLORS.size)
            Mode.START -> {
                start = (start + step).coerceIn(0L, Tape.LENGTH - MIN_SLICE_FRAMES.toLong()).toInt()
                end = max(end, start + MIN_SLICE_FRAMES)
                cursor = start
            }
            Mode.END -> {
                end = (end + step).coerceIn(start + MIN_SLICE_FRAMES.toLong(), Tape.LENGTH.toLong()).toInt()
                cursor = end
            }
            Mode.RECORD_POSITION -> cursor = (cursor + step).coerceIn(0L, Tape.LENGTH - MIN_SLICE_FRAMES.toLong()).toInt()
            Mode.IMPORT_POSITION -> cursor = max(0L, min(Tape.LENGTH.toLong() - (imported?.size ?: 0), cursor + step)).toInt()
            else -> {}
        }
        if (mode != previousMode || wheelValue() != previousValue) {
            if (isTimelineMode()) { sequencer.stopSelectionPreview(); clearSourcePreview(); if (mode == Mode.START || mode == Mode.END) updateSliceLive() }
            haptic()
        }
    }

    private fun envelopeStep(value: Int, delta: Int): Int {
        var v = value
        repeat(abs(delta)) {
            val up = delta > 0
            val base = if (up) v else v - 1
            val step = when { base < 20 -> 1; base < 100 -> 5; base < 500 -> 10; else -> 50 }
            v = if (up) (v + step) / step * step else (v - 1) / step * step
        }
        return v.coerceIn(0, MAX_ENVELOPE_MS)
    }

    private fun wheelValue(): Long = when (mode) {
        Mode.HOME -> track.toLong()
        Mode.BPM -> sequencer.bpm().toLong()
        Mode.SWING -> sequencer.swing().toLong()
        Mode.VIBRATION -> vibrationIntensity.toLong()
        Mode.SENSITIVITY -> wheelSensitivity.toLong()
        Mode.FX_VALUE -> effectValue.toLong()
        Mode.IMPORT_START -> importStart
        Mode.IMPORT_END -> importEnd
        Mode.START -> start.toLong()
        Mode.END -> end.toLong()
        Mode.RECORD_POSITION, Mode.IMPORT_POSITION -> cursor.toLong()
        else -> selection.toLong()
    }

    fun setVibrationIntensity(intensity: Int) {
        vibrationIntensity = intensity.coerceIn(0, 100)
        if (vibrationIntensity == 0) {
            vibrationEffect = null
            vibrator?.cancel()
        } else {
            val amplitudeControl = vibrator?.hasAmplitudeControl() == true
            vibrationEffect = VibrationEffect.createOneShot((if (amplitudeControl) PULSE_MS else MIN_PULSE_MS + vibrationIntensity * PULSE_RANGE_MS / 100).toLong(),
                if (amplitudeControl) max(1, Math.round(vibrationIntensity * MAX_AMPLITUDE / 100f)) else VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    private fun haptic() {
        val effect = vibrationEffect
        val motor = vibrator
        if (mode == Mode.SYNC_CLAP || !foreground || effect == null || motor == null || !motor.hasVibrator()
            || Settings.System.getInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastHaptic < MIN_HAPTIC_GAP_MS) return
        lastHaptic = now
        if (Build.VERSION.SDK_INT >= 33) motor.vibrate(effect, vibrationAttributes!!)
        else @Suppress("DEPRECATION") motor.vibrate(effect, hapticAttributes)
    }

    fun timelineIncrement(): Long {
        val minimum = (Tape.RATE / 1000).toLong()
        return if (fine) minimum else max(minimum, Math.round(timelineSpan() / (TIMELINE_STEPS.toDouble() * minimum)) * minimum)
    }

    fun toggleFine() {
        if (!busy && isTimelineMode()) { fine = !fine; haptic() }
    }

    fun toggleZoom() {
        if (!busy && isTimelineMode()) {
            autoHomeZoom = false
            zoom = !zoom
            zoomFrames = DEFAULT_TIMELINE_FRAMES.toLong()
            haptic()
        }
    }

    private fun resetTimelineZoom() {
        zoom = true
        autoHomeZoom = true
        refreshHomeZoom()
    }

    private fun refreshHomeZoom() {
        if (!autoHomeZoom) return
        val overview = TimelineWindow.overview(Tape.LENGTH.toLong(), regions.snapshot(), DEFAULT_TIMELINE_FRAMES.toLong())
        homeWindowStart = overview.start; zoomFrames = overview.span
    }

    fun homeTimelineStart(): Int {
        val span = timelineSpan()
        if (autoHomeZoom) return homeWindowStart.coerceIn(0L, max(0L, Tape.LENGTH - span)).toInt()
        val controls = sequencer.tracks[track]
        return TimelineWindow.start(Tape.LENGTH.toLong(), span, controls.start.toLong(), controls.start.toLong(), controls.end.toLong()).toInt()
    }

    private fun fitTimelineClip(start: Long, end: Long) {
        if (zoom) zoomFrames = max(zoomFrames, TimelineWindow.fit(timelineLength(), timelineSpan(), start, end))
    }

    private fun timelineLength(): Long {
        val source = importSource
        return if ((mode == Mode.IMPORT_START || mode == Mode.IMPORT_END) && source != null) source.frames else Tape.LENGTH.toLong()
    }

    fun timelineSpan(): Long = if (zoom) min(timelineLength(), zoomFrames) else timelineLength()

    fun zoomByWheel(direction: Int) {
        if (busy || direction == 0) return
        val full = timelineLength()
        val current = timelineSpan()
        autoHomeZoom = false
        zoomFrames = min(full, max(MIN_SLICE_FRAMES.toLong(), Math.round(if (direction > 0) current * ZOOM_STEP else current / ZOOM_STEP)))
        zoom = true
        if (timelineSpan() != current) haptic()
    }

    fun isTimelineMode() = mode == Mode.START || mode == Mode.END || mode == Mode.RECORD_POSITION || mode == Mode.IMPORT_POSITION || mode == Mode.RECORDING || mode == Mode.IMPORT_START || mode == Mode.IMPORT_END

    fun confirm() {
        if (busy) return
        sequencer.stopSelectionPreview()
        haptic()
        when (mode) {
            Mode.HOME -> { mode = Mode.MENU; selection = 0 }
            Mode.PAGES -> pagePrompt = true
            Mode.PAGE_PASTE -> applyPaste()
            Mode.PAGE_RESET -> applyReset()
            Mode.MENU -> openMenu()
            Mode.ADD_SAMPLE -> openAddSample()
            Mode.DEVICE_RECORD -> if (DeviceCaptureService.active()) DeviceCaptureService.stop(this) else requestDeviceRecording()
            Mode.SYSTEM -> openSystem()
            Mode.EXPORT -> openExport()
            Mode.EXPORT_LOOPS -> openExportLoops()
            Mode.BACK -> leaveBack()
            Mode.SYNC -> {
                setSyncEnabled(selection == 0)
                if (syncEnabled) beginClap()
                else { mode = Mode.MENU; selection = MENU_SYNC; message = "SYNC OFF" }
            }
            Mode.SYNC_CLAP ->
                if (clapClock != null) { mode = Mode.HOME; message = "CLAP SYNC READY / PLAY JOINS NEXT LOOP" }
                else if (clapCapture == null) beginClap()
            Mode.VIBRATION -> {
                settings.edit().putInt("vibrationIntensity", vibrationIntensity).apply()
                mode = Mode.SYSTEM; selection = SYSTEM_VIBRATION
            }
            Mode.SENSITIVITY -> {
                settings.edit().putInt("wheelSensitivity", wheelSensitivity).apply()
                mode = Mode.SYSTEM; selection = SYSTEM_WHEEL
            }
            Mode.IMPORT_START -> mode = Mode.IMPORT_END
            Mode.IMPORT_END -> prepareImport()
            Mode.EFFECTS -> openEffect()
            Mode.FX_ADD -> openAddedEffect()
            Mode.FX_REORDER -> confirmReorder()
            Mode.FX_VALUE -> commitEffect()
            Mode.PROJECTS -> when (selection) {
                PROJECT_NEW -> requestNewSession()
                PROJECT_OPEN -> if (browseListFor(Mode.PROJECT_OPEN).isEmpty()) error("No other tapes to open yet") else { mode = Mode.PROJECT_OPEN; selection = 0 }
                PROJECT_EDIT -> { mode = Mode.PROJECT_EDIT; selection = sessionIndex(session) }
                PROJECT_EXPORT -> { mode = Mode.EXPORT; selection = 0 }
                else -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")).addCategory(Intent.CATEGORY_OPENABLE), REQUEST_IMPORT_PROJECT)
            }
            Mode.PROJECT_OPEN -> browsed()?.let { openProject(it) }
            Mode.PROJECT_EDIT -> { editTarget = sessions[selection]; mode = Mode.PROJECT_EDIT_MENU; selection = 0 }
            Mode.PROJECT_EDIT_MENU -> when (selection) {
                EDIT_RENAME -> renameProject()
                EDIT_COLOR -> { colorForNew = false; mode = Mode.PROJECT_COLOR; selection = editTarget?.let { colorIndex(it) } ?: 0 }
                else -> mode = Mode.PROJECT_DELETE
            }
            Mode.PROJECT_COLOR ->
                if (colorForNew) {
                    val color = selection
                    instrument.insertCassette(pendingName, CASSETTE_COLORS[color], session.name(), cassetteColor(session))
                    createSession(pendingName, color)
                } else recolorProject(selection)
            Mode.PROJECT_DELETE -> deleteProject()
            Mode.START, Mode.END -> saveSlice()
            Mode.RECORD_POSITION -> requestRecording()
            Mode.RECORDING -> { audio.stopRecording(); message = "FINISHING RECORDING…" }
            Mode.IMPORT_POSITION -> placeImport()
            Mode.TEMPO ->
                if (selection == TEMPO_BPM) { tempoBefore = sequencer.bpm(); mode = Mode.BPM }
                else { swingBefore = sequencer.swing(); mode = Mode.SWING }
            Mode.BPM -> { save(); mode = Mode.TEMPO; selection = TEMPO_BPM }
            Mode.SWING -> { save(); mode = Mode.TEMPO; selection = TEMPO_SWING }
            else -> {}
        }
    }

    fun cancel() {
        if (busy) return
        sequencer.stopSelectionPreview()
        when (mode) {
            Mode.PAGES -> {
                if (!pagePrompt) mode = Mode.HOME
                pagePrompt = false; swapSource = -1
            }
            Mode.PAGE_COPIED -> { mode = Mode.PAGES; selection = pageAction }
            Mode.PAGE_RESET -> { mode = Mode.PAGES; selection = pageAction; pasteTarget = -1 }
            Mode.PAGE_PASTE -> { mode = Mode.PAGE_COPIED; selection = pageAction; pasteTarget = -1 }
            Mode.HOME -> { sequencer.stop(); message = "STOPPED" }
            Mode.DEVICE_RECORD ->
                if (DeviceCaptureService.active()) DeviceCaptureService.stop(this)
                else if (!deviceCaptureStarting) { mode = Mode.ADD_SAMPLE; selection = ADD_DEVICE }
            Mode.ADD_SAMPLE -> { mode = Mode.MENU; selection = MENU_ADD_SAMPLE }
            Mode.RECORDING -> { audio.stopRecording(); message = "FINISHING RECORDING…" }
            Mode.END, Mode.START -> {
                rollbackDraft(); scheduleLive()
                mode = Mode.HOME; selection = 0
            }
            Mode.IMPORT_POSITION -> mode = Mode.IMPORT_END
            Mode.IMPORT_END -> mode = Mode.IMPORT_START
            Mode.IMPORT_START -> {
                imported = null; importSource = null; importWave = null
                clearSourcePreview()
                mode = Mode.ADD_SAMPLE; selection = ADD_IMPORT
            }
            Mode.RECORD_POSITION -> { mode = Mode.ADD_SAMPLE; selection = ADD_MICROPHONE }
            Mode.TEMPO -> { mode = Mode.MENU; selection = MENU_TEMPO }
            Mode.BPM -> { sequencer.tempo(tempoBefore); mode = Mode.TEMPO; selection = TEMPO_BPM }
            Mode.SWING -> { sequencer.swing(swingBefore); mode = Mode.TEMPO; selection = TEMPO_SWING }
            Mode.EXPORT -> { mode = Mode.PROJECTS; selection = PROJECT_EXPORT }
            Mode.EXPORT_LOOPS -> { mode = Mode.EXPORT; selection = EXPORT_WAV }
            Mode.SYSTEM -> { mode = Mode.MENU; selection = MENU_SYSTEM }
            Mode.BACK -> leaveBack()
            Mode.SYNC -> { mode = Mode.MENU; selection = MENU_SYNC }
            Mode.SYNC_CLAP -> {
                cancelClap()
                if (clapClock != null) { mode = Mode.HOME; message = "CLAP SYNC READY / PLAY JOINS NEXT LOOP" }
                else { syncEnabled = false; mode = Mode.MENU; selection = MENU_SYNC }
            }
            Mode.VIBRATION -> { setVibrationIntensity(vibrationBefore); mode = Mode.SYSTEM; selection = SYSTEM_VIBRATION }
            Mode.SENSITIVITY -> { wheelSensitivity = wheelBefore; mode = Mode.SYSTEM; selection = SYSTEM_WHEEL }
            Mode.PROJECTS -> { mode = Mode.MENU; selection = MENU_PROJECT }
            Mode.PROJECT_OPEN -> { mode = Mode.PROJECTS; selection = PROJECT_OPEN }
            Mode.PROJECT_EDIT -> { mode = Mode.PROJECTS; selection = PROJECT_EDIT }
            Mode.PROJECT_EDIT_MENU -> { mode = Mode.PROJECT_EDIT; selection = sessionIndex(editTarget) }
            Mode.PROJECT_COLOR ->
                if (colorForNew) { mode = Mode.PROJECTS; selection = PROJECT_NEW }
                else { mode = Mode.PROJECT_EDIT_MENU; selection = EDIT_COLOR }
            Mode.PROJECT_DELETE -> { mode = Mode.PROJECT_EDIT_MENU; selection = EDIT_DELETE }
            Mode.EFFECTS ->
                if (effectGroup >= 0) { selection = stackRow(effectGroup); effectGroup = -1 }
                else { mode = if (stepFromHold) Mode.HOME else Mode.MENU; selection = 0; stepTarget = -1; deletedEffects.clear() }
            Mode.FX_ADD -> { mode = Mode.EFFECTS; selection = effectStack().size }
            Mode.FX_REORDER -> { effectHeld = -1; mode = Mode.EFFECTS; save() }
            Mode.FX_VALUE -> {
                rollbackDraft(); scheduleLive()
                returnToEffects()
            }
            else -> mode = Mode.HOME
        }
        haptic()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (mode == Mode.HOME && !busy) super.onBackPressed() else cancel()
    }

    private fun openMenu() {
        when (selection) {
            MENU_EFFECTS -> { stepTarget = -1; stepFromHold = false; effectGroup = -1; effectHeld = -1; deletedEffects.clear(); mode = Mode.EFFECTS; selection = 0 }
            MENU_ADD_SAMPLE -> { mode = Mode.ADD_SAMPLE; selection = 0 }
            MENU_TEMPO -> { mode = Mode.TEMPO; selection = 0 }
            MENU_SYNC -> if (clapClock == null) { setSyncEnabled(true); beginClap() } else { mode = Mode.SYNC; selection = 0 }
            MENU_PROJECT -> { mode = Mode.PROJECTS; selection = 0 }
            MENU_SYSTEM -> { mode = Mode.SYSTEM; selection = 0 }
            MENU_BACK -> { mode = Mode.BACK; selection = 0 }
        }
    }

    fun tempoItems() = arrayOf("BPM / " + sequencer.bpm(), "Swing / " + sequencer.swing() + "%")

    fun hasTimelineZoom() = mode == Mode.HOME || mode == Mode.START || mode == Mode.END || mode == Mode.IMPORT_START || mode == Mode.IMPORT_END || mode == Mode.IMPORT_POSITION || mode == Mode.RECORD_POSITION

    fun editingStep(step: Int) = stepTarget == step && (mode == Mode.EFFECTS || mode == Mode.FX_ADD || mode == Mode.FX_REORDER || mode == Mode.FX_VALUE)

    private fun openAddSample() {
        when (selection) {
            ADD_MICROPHONE -> { sequencer.stop(); cursor = sequencer.tracks[track].start; mode = Mode.RECORD_POSITION }
            ADD_DEVICE -> { sequencer.stop(); mode = Mode.DEVICE_RECORD; message = "RECORD TO START RECORDING" }
            else -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE), REQUEST_IMPORT_AUDIO)
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemTheme() {
        val color = InstrumentView.surface(darkTheme)
        val decor = window.decorView
        window.setBackgroundDrawable(ColorDrawable(color))
        window.navigationBarColor = color
        window.statusBarColor = color
        if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = color
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30 && window.insetsController != null) {
            val appearance = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            window.insetsController!!.setSystemBarsAppearance(if (darkTheme) 0 else appearance, appearance)
        } else {
            val appearance = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            decor.systemUiVisibility = if (darkTheme) decor.systemUiVisibility and appearance.inv() else decor.systemUiVisibility or appearance
        }
    }

    private fun openSystem() {
        when (selection) {
            SYSTEM_VIBRATION -> { vibrationBefore = vibrationIntensity; mode = Mode.VIBRATION }
            SYSTEM_WHEEL -> { wheelBefore = wheelSensitivity; mode = Mode.SENSITIVITY }
            SYSTEM_THEME -> {
                darkTheme = !darkTheme
                settings.edit().putBoolean("darkTheme", darkTheme).apply()
                applySystemTheme()
            }
            SYSTEM_LEFT_HAND -> {
                leftHandMode = !leftHandMode
                settings.edit().putBoolean("leftHandMode", leftHandMode).apply()
            }
            SYSTEM_BATTERIES -> if (batteries.found) { batteries.useBatteries = !batteries.useBatteries; batteryTickAt = SystemClock.uptimeMillis(); batteries.save() } else toggleFlag()
            else -> toggleFlag()
        }
    }

    fun exportLoopItems() = arrayOf("One loop", "All pads / " + sequencer.passesForAllPads() + " loops")

    private fun fileName() = session.name().replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun openExport() {
        if (selection == EXPORT_PROJECT) {
            save()
            startActivityForResult(exportIntent("application/zip", fileName() + ".tapedeck.zip"), REQUEST_EXPORT_PROJECT)
            return
        }
        val midi = selection == EXPORT_MIDI
        exportPasses = 1
        if (!midi && sequencer.passesForAllPads() > 1) { mode = Mode.EXPORT_LOOPS; selection = LOOPS_ALL; return }
        pickExport(midi)
    }

    private fun openExportLoops() {
        exportPasses = if (selection == LOOPS_ONE) 1 else sequencer.passesForAllPads()
        pickExport(false)
    }

    private fun pickExport(midi: Boolean) {
        startActivityForResult(exportIntent(if (midi) "audio/midi" else "audio/wav", fileName() + if (midi) ".mid" else ".wav"), if (midi) REQUEST_EXPORT_MIDI else REQUEST_EXPORT_WAV)
    }

    private fun exportIntent(type: String, title: String): Intent {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, title)
        @Suppress("DEPRECATION")
        val folder = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "Tape Deck Sampler")
        try {
            if (folder.isDirectory || folder.mkdirs()) intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + android.os.Environment.DIRECTORY_MUSIC + "/Tape Deck Sampler"))
        } catch (_: SecurityException) {}
        return intent
    }

    fun screenPath(): String = when (mode) {
        Mode.HOME -> session.name()
        Mode.PAGES -> "SEQUENCE PAGES"
        Mode.PAGE_COPIED -> "SEQUENCE PAGES / COPY"
        Mode.PAGE_PASTE -> "SEQUENCE PAGES / PASTE"
        Mode.PAGE_RESET -> "SEQUENCE PAGES / RESET"
        Mode.MENU -> "MENU"
        Mode.ADD_SAMPLE -> "ADD SAMPLE"
        Mode.DEVICE_RECORD -> "RECORD / DEVICE"
        Mode.SYSTEM -> "SYSTEM"
        Mode.EXPORT -> "PROJECT / EXPORT"
        Mode.EXPORT_LOOPS -> "PROJECT / EXPORT / WAV"
        Mode.BACK -> "BACK"
        Mode.SYNC -> "SYNC"
        Mode.SYNC_CLAP -> "SYNC / CLAP"
        Mode.VIBRATION -> "SYSTEM / VIBRATION"
        Mode.SENSITIVITY -> "SYSTEM / WHEEL"
        Mode.EFFECTS, Mode.FX_ADD, Mode.FX_REORDER, Mode.FX_VALUE -> {
            val target = if (stepTarget < 0) String.format(Locale.US, "PAGE %02d / TRACK %d", sequencer.selectedPage() + 1, track + 1) else "PAD " + String.format(Locale.US, "%02d", stepTarget + 1)
            target + " / " + when {
                mode == Mode.FX_ADD -> "ADD EFFECT"
                mode == Mode.FX_REORDER -> "ORDER"
                effectGroup < 0 -> "EFFECTS"
                else -> FX_NAMES[effectGroup].uppercase(Locale.US)
            }
        }
        Mode.START, Mode.END -> "SLICE / " + if (mode == Mode.START) "START" else "END"
        Mode.IMPORT_START -> "IMPORT / START"
        Mode.IMPORT_END -> "IMPORT / END"
        Mode.IMPORT_POSITION -> "IMPORT / PLACE"
        Mode.RECORD_POSITION -> "RECORD / POSITION"
        Mode.RECORDING -> "RECORDING"
        Mode.TEMPO -> "TEMPO"
        Mode.BPM -> "TEMPO / BPM"
        Mode.SWING -> "TEMPO / SWING"
        Mode.PROJECTS -> "PROJECT"
        Mode.PROJECT_OPEN -> "PROJECT / OPEN"
        Mode.PROJECT_EDIT -> "PROJECT / EDIT"
        Mode.PROJECT_EDIT_MENU -> "PROJECT / EDIT TAPE"
        Mode.PROJECT_COLOR -> if (colorForNew) "PROJECT / NEW / COLOR" else "PROJECT / EDIT / COLOR"
        Mode.PROJECT_DELETE -> "PROJECT / DELETE"
        else -> ""
    }

    private fun cancelClap() {
        clapGeneration++
        clapCapture?.let {
            it.cancel(); clapCapture = null
            clapStatus = "INTERRUPTED / SELECT TO RETRY"
        }
    }

    private fun beginClap() {
        cancelClap()
        sequencer.stop()
        clapClock = null; audio.setClapClock(null)
        mode = Mode.SYNC_CLAP
        clapStatus = "PREPARING MICROPHONE"
        instrument.invalidate()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_CLAP)
            return
        }
        if (!acquireFocus()) { clapStatus = "AUDIO UNAVAILABLE / SELECT TO RETRY"; return }
        vibrator?.cancel()
        val generation = clapGeneration
        val capture = ClapCapture()
        clapCapture = capture
        val source = if ("true" == audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))
            android.media.MediaRecorder.AudioSource.UNPROCESSED else android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
        capture.start(source, object : ClapCapture.Listener {
            override fun ready() {
                runOnUiThread {
                    if (generation != clapGeneration || !foreground) return@runOnUiThread
                    clapStatus = "READY, CLAP YOUR HANDS ONCE"
                    instrument.invalidate()
                }
            }

            override fun finished(origin: Long, error: String?) {
                runOnUiThread {
                    if (generation != clapGeneration || !foreground) return@runOnUiThread
                    clapCapture = null
                    if (error == null) {
                        clapBootOrigin = SystemClock.elapsedRealtimeNanos() - (System.nanoTime() - origin)
                        clapClock = ClapClock(origin)
                        audio.setClapClock(clapClock)
                        clapStatus = "CLAP HEARD, DEVICE SYNCED"
                        statJams++; saveStats()
                        message = "CLAP SYNC READY / PLAY JOINS NEXT LOOP"
                    } else clapStatus = error
                    instrument.invalidate()
                }
            }
        })
    }

    private fun requestDeviceRecording() {
        if (Build.VERSION.SDK_INT < 29) { error("Device recording requires Android 10 or later"); return }
        if (DeviceCaptureService.active() || deviceCaptureStarting) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_DEVICE)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val consent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
        startActivityForResult(consent, REQUEST_CAPTURE_CONSENT)
    }

    private fun refreshDeviceCapture() {
        if (!foreground || !ready) return
        if (busy) {
            if (DeviceCaptureService.active() || DeviceCaptureService.pending(this)) instrument.postDelayed({ refreshDeviceCapture() }, CAPTURE_POLL_MS)
            return
        }
        if (DeviceCaptureService.active()) {
            deviceCaptureStarting = false
            mode = Mode.DEVICE_RECORD
        } else if (!deviceCaptureStarting) {
            val failure = DeviceCaptureService.error(this)
            if (DeviceCaptureService.pending(this)) {
                DeviceCaptureService.consumed(this)
                decode(Uri.fromFile(DeviceCaptureService.path(this)), "Device recording", true)
                if (failure.isNotEmpty()) Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
            } else if (failure.isNotEmpty()) {
                DeviceCaptureService.consumed(this)
                mode = Mode.DEVICE_RECORD
                error(failure)
            }
        }
        instrument.invalidate()
    }

    private fun requestRecording() {
        cancelLive()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_RECORD)
            return
        }
        if (!acquireFocus()) return
        recordStart = cursor
        recordPosition = cursor
        recordingRegions = regions
        recordingId = "record:" + java.util.UUID.randomUUID()
        mode = Mode.RECORDING
        audio.record(cursor)
        instrument.invalidate()
    }

    private var grantedRequest = -1

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(request, permissions, grants)
        val granted = grants.isNotEmpty() && grants[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            grantedRequest = request
            if (foreground) continueGranted()
            return
        }
        when (request) {
            PERMISSION_DEVICE -> error("Microphone permission is required by Android to capture device audio")
            PERMISSION_CLAP -> if (mode == Mode.SYNC_CLAP) { clapStatus = "MICROPHONE ACCESS REQUIRED"; instrument.invalidate() }
            PERMISSION_RECORD -> error("Microphone access is needed to record. Enable it in Android app settings.")
        }
    }

    private fun continueGranted() {
        val request = grantedRequest
        grantedRequest = -1
        if (request == PERMISSION_DEVICE && mode == Mode.DEVICE_RECORD) requestDeviceRecording()
        else if (request == PERMISSION_CLAP && mode == Mode.SYNC_CLAP) beginClap()
        else if (request == PERMISSION_RECORD && mode == Mode.RECORD_POSITION) requestRecording()
        if (ready) instrument.invalidate()
    }

    override fun recorded(position: Int) {
        recordPosition = position
        recordingRegions?.place(recordStart, position, recordingId!!, "Recording")
        waveVersion++
    }

    override fun recordingStarted() { recordingRegions?.checkpoint() }

    override fun finished(error: String?) {
        runOnUiThread {
            if (recordPosition > recordStart) {
                synchronized(sequencer) { sequencer.tracks[track].start = recordStart; sequencer.tracks[track].end = recordPosition }
                save()
            }
            cursor = recordPosition
            mode = Mode.HOME
            changed("RECORDED " + seconds((recordPosition - recordStart).toLong()) + "s / TRACK " + (track + 1))
            if (error != null) error(error)
        }
    }

    override fun audioError(error: String?) { runOnUiThread { error(error ?: "Audio output failed") } }

    private fun openOutput(uri: Uri): OutputStream = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open export destination")

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(request, result, data)
        if (request == REQUEST_CAPTURE_CONSENT) {
            if (result == RESULT_OK && data != null) {
                sequencer.stop()
                deviceCaptureStarting = true
                mode = Mode.DEVICE_RECORD
                try {
                    startForegroundService(Intent(this, DeviceCaptureService::class.java).putExtra("result", result).putExtra("consent", data))
                } catch (e: Exception) { deviceCaptureStarting = false; error("Could not start device recording: " + e.message) }
            } else { mode = Mode.DEVICE_RECORD; message = "CAPTURE CANCELLED / RECORD TO RETRY" }
            return
        }
        val uri = data?.data
        if (result != RESULT_OK || uri == null) return
        when (request) {
            REQUEST_IMPORT_PROJECT -> importProject(uri)
            REQUEST_IMPORT_AUDIO -> decode(uri, displayName(uri))
            REQUEST_EXPORT_WAV -> {
                val passes = exportPasses
                work("EXPORTING WAV", {
                    val rendered = File.createTempFile("sequence-", ".wav", cacheDir)
                    try {
                        WavExporter.export(rendered, sequencer, passes)
                        openOutput(uri).use { java.nio.file.Files.copy(rendered.toPath(), it) }
                    } finally { java.nio.file.Files.deleteIfExists(rendered.toPath()) }
                }) {
                    message = "EXPORTED / PROJECT WAV"
                    if (mode == Mode.EXPORT_LOOPS) { mode = Mode.EXPORT; selection = EXPORT_WAV }
                }
            }
            REQUEST_EXPORT_PROJECT -> {
                val source = tape
                val entry = session
                val stored = preferences.all
                val color = colorIndex(entry)
                work("EXPORTING PROJECT", {
                    val values = JSONObject()
                    for ((key, value) in stored) values.put(key, value)
                    val project = JSONObject().put("format", "tapedecksampler-project").put("version", 1)
                        .put("name", entry.name()).put("color", CASSETTE_NAMES[color]).put("sampleRate", Tape.RATE).put("seconds", Tape.LENGTH / Tape.RATE)
                        .put("settings", values)
                    openOutput(uri).use { raw ->
                        val zip = java.util.zip.ZipOutputStream(raw)
                        zip.putNextEntry(java.util.zip.ZipEntry("project.json"))
                        zip.write(project.toString(2).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        zip.putNextEntry(java.util.zip.ZipEntry("tape.wav"))
                        source.export(zip)
                        zip.closeEntry()
                        zip.finish()
                    }
                }) { message = "EXPORTED / PROJECT" }
            }
            REQUEST_EXPORT_MIDI -> work("EXPORTING MIDI", { openOutput(uri).use { MidiExporter.export(it, sequencer) } }) { message = "EXPORTED / PROJECT MIDI" }
        }
    }

    private var importedEntry: SessionStore.Entry? = null

    fun importProject(uri: Uri) {
        importedEntry = null
        work("IMPORTING PROJECT", {
            var project: JSONObject? = null
            val wave = File.createTempFile("import-", ".wav", cacheDir)
            try {
                (contentResolver.openInputStream(uri) ?: throw IOException("Cannot open project file")).use { raw ->
                    val zip = java.util.zip.ZipInputStream(raw)
                    while (true) {
                        val item = zip.nextEntry ?: break
                        if (item.name == "project.json") project = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        else if (item.name == "tape.wav") java.nio.file.Files.copy(zip, wave.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                val loaded = project
                if (loaded == null || "tapedecksampler-project" != loaded.optString("format") || wave.length() == 0L) throw IOException("Not a Tape Deck Sampler project")
                var name = loaded.optString("name", "").trim()
                if (name.isEmpty()) name = SessionStore.nextDefaultName(sessions)
                if (name.length > SessionStore.MAX_NAME_LENGTH) name = name.substring(0, SessionStore.MAX_NAME_LENGTH).trim()
                val entry = sessionStore.create(name, CASSETTE_NAMES[cassetteIndex(loaded.optString("color", ""), 0)])
                try {
                    java.nio.file.Files.move(wave.toPath(), entry.tape.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    Tape(entry.tape).use { it.flush() }
                    val editor = sessionPreferences(entry).edit()
                    val values = loaded.optJSONObject("settings")
                    if (values != null) {
                        for (key in values.keys()) {
                            when (val value = values.get(key)) {
                                is String -> editor.putString(key, value)
                                is Boolean -> editor.putBoolean(key, value)
                                is Int -> editor.putInt(key, value)
                                is Long -> editor.putLong(key, value)
                                is Number -> editor.putFloat(key, value.toFloat())
                            }
                        }
                    }
                    if (!editor.commit()) throw IOException("Cannot save imported project")
                    importedEntry = entry
                } catch (e: Exception) {
                    try { sessionStore.delete(entry) } catch (_: Exception) {}
                    deleteSharedPreferences(entry.id)
                    throw e
                }
            } finally { java.nio.file.Files.deleteIfExists(wave.toPath()) }
        }) {
            refreshSessions()
            val id = importedEntry?.id
            sessions.lastOrNull { it.id == id }?.let { openProject(it) }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {}
        return "sample.wav"
    }

    fun decode(uri: Uri, name: String) { decode(uri, name, false) }

    private fun decode(uri: Uri, name: String, recording: Boolean) {
        sequencer.stop()
        work("READING SOURCE WAVEFORM", {
            val source = SampleDecoder.inspect(this, uri)
            importSource = source
            if (source.frames < MIN_SLICE_FRAMES) throw IOException("Sample must be at least 1 ms long")
        }) {
            importName = name
            clearSourcePreview()
            importStart = 0
            importEnd = min(if (recording) Tape.LENGTH.toLong() else Tape.RATE * 5L, importSource!!.frames)
            fine = false
            mode = Mode.IMPORT_START
        }
    }

    private fun prepareImport() {
        val sourceStart = importStart
        val sourceEnd = importEnd
        val source = importSource
        work("PREPARING SELECTED SAMPLE", {
            val samples = SampleDecoder.decode(this, source, sourceStart, sourceEnd)
            imported = samples
            importId = SampleRegions.identity(samples)
            val summary = WaveSummary()
            for (value in samples) summary.accept(value)
            summary.finish()
            importWave = summary
        }) {
            val samples = imported!!
            importEnd = importStart + samples.size
            clearSourcePreview()
            importColor = regions.color(importId!!)
            cursor = min(sequencer.tracks[track].start, Tape.LENGTH - samples.size)
            mode = Mode.IMPORT_POSITION
        }
    }

    private fun placeImport() {
        val data = imported ?: return
        cancelLive()
        val position = cursor
        if (position < 0 || position + data.size > Tape.LENGTH) { error("Selected sample must fit within the tape"); return }
        val count = data.size
        work("PLACING SAMPLE", {
            tape.checkpoint(); tape.write(position, data, count); tape.flush()
            regions.checkpoint(); regions.place(position, position + count, importId!!, importName)
        }) {
            synchronized(sequencer) { sequencer.tracks[track].start = position; sequencer.tracks[track].end = position + count }
            imported = null
            importSource = null
            importWave = null
            mode = Mode.HOME
            save()
            changed("PLACED " + seconds(count.toLong()) + "s / " + importName)
        }
    }

    private fun changed(text: String) {
        refreshHomeZoom()
        waveVersion++
        message = text
        if (ready) instrument.invalidate()
        preparePlayback()
    }

    private fun work(text: String, action: Work, done: Runnable) {
        busy = true
        message = text
        instrument.invalidate()
        worker.execute {
            try {
                action.run()
                runOnUiThread { busy = false; if (!isDestroyed) { done.run(); instrument.invalidate() } }
            } catch (e: Exception) {
                runOnUiThread { busy = false; error(e.message ?: "Operation failed") }
            }
        }
    }

    private fun error(text: String?) {
        message = text ?: "Operation failed"
        if (ready) instrument.invalidate()
        if (!isDestroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val DEFAULT_TIMELINE_FRAMES = Tape.RATE * 10
        const val MIN_SLICE_FRAMES = 48
        const val DEFAULT_WHEEL = 100
        const val MIN_WHEEL = 25
        const val MAX_WHEEL = 200
        const val DEFAULT_VIBRATION = 25
        private const val PULSE_MS = 12
        private const val MIN_PULSE_MS = 4
        private const val PULSE_RANGE_MS = 16
        private const val MAX_AMPLITUDE = 255
        private const val MIN_HAPTIC_GAP_MS = 24
        private const val LIVE_UPDATE_MS = 60L
        private const val CAPTURE_POLL_MS = 100L
        private const val TIMELINE_STEPS = 100
        private const val ZOOM_STEP = 0.8
        private const val ERROR_PADDING = 32
        private const val NAME_PADDING_X = 24
        private const val NAME_PADDING_Y = 20
        const val MENU_EFFECTS = 0
        const val MENU_ADD_SAMPLE = 1
        const val MENU_TEMPO = 2
        const val MENU_SYNC = 3
        const val MENU_PROJECT = 4
        const val MENU_SYSTEM = 5
        const val MENU_BACK = 6
        const val ADD_MICROPHONE = 0
        const val ADD_DEVICE = 1
        const val ADD_IMPORT = 2
        const val PROJECT_NEW = 0
        const val PROJECT_OPEN = 1
        const val PROJECT_EDIT = 2
        const val PROJECT_EXPORT = 3
        const val EDIT_RENAME = 0
        const val EDIT_COLOR = 1
        const val EDIT_DELETE = 2
        const val EXPORT_MIDI = 0
        const val EXPORT_WAV = 1
        const val EXPORT_PROJECT = 2
        const val LOOPS_ONE = 0
        const val LOOPS_ALL = 1
        const val TEMPO_BPM = 0
        const val TEMPO_SWING = 1
        const val SYSTEM_VIBRATION = 0
        const val SYSTEM_WHEEL = 1
        const val SYSTEM_THEME = 2
        const val SYSTEM_LEFT_HAND = 3
        const val SYSTEM_BATTERIES = 4
        private const val BATTERY_TICK_MS = 1000L
        private const val BATTERY_SAVE_MS = 30_000L
        private const val CHIME_MS = 760
        private const val CHIME_LEVEL = 0.2
        private val CHIME_NOTES = doubleArrayOf(784.0, 987.8, 1174.7, 1568.0)
        private val CHIME_STARTS = intArrayOf(0, 70, 140, 230)
        private val CHIME_LENGTHS = intArrayOf(110, 110, 120, 530)
        private const val UNLOCK_BLINKS = 3
        private const val READY_BLINKS = 2
        private const val REQUEST_IMPORT_AUDIO = 20
        private const val REQUEST_EXPORT_MIDI = 23
        private const val REQUEST_EXPORT_WAV = 24
        private const val REQUEST_EXPORT_PROJECT = 25
        private const val REQUEST_IMPORT_PROJECT = 26
        private const val REQUEST_CAPTURE_CONSENT = 33
        private const val PERMISSION_RECORD = 30
        private const val PERMISSION_CLAP = 31
        private const val PERMISSION_DEVICE = 32
        const val STEP_MASK = (1 shl Sequencer.STEPS) - 1
        const val EFFECT_MASK = (1 shl Sequencer.Effects.GROUPS) - 1
        private const val FX_VERSION = 2
        private const val LEGACY_WET_GROUP = 9
        private const val LEGACY_SHIFT_GROUP = 16
        const val MIN_FILTER_HZ = 20
        const val MAX_FILTER_HZ = 20000
        const val FILTER_STEPS = 100
        const val MAX_ENVELOPE_MS = 5000
        const val MAX_EVERY = 16
        const val MIN_WOBBLE_RATE = 5
        const val MAX_WOBBLE_RATE = 200
        const val WOBBLE_RATE_PER_HZ = 10f
        const val MAX_SHIFT_MS = 2000
        const val MAX_RETUNE_MS = 500
        const val MAX_NUDGE_MS = 100
        const val FAST_STEP = 5
        const val FX_SPEED = 0
        const val FX_PITCH = 1
        const val FX_REVERB = 2
        const val FX_DISTORTION = 3
        const val FX_DISTORTION_MIX = 4
        const val FX_VOLUME = 5
        const val FX_HIGH_PASS = 6
        const val FX_LOW_PASS = 7
        const val FX_DELAY_TIME = 8
        const val FX_DELAY_FEEDBACK = 9
        const val FX_DELAY_MIX = 10
        const val FX_ATTACK = 11
        const val FX_DECAY = 12
        const val FX_SUSTAIN = 13
        const val FX_RELEASE = 14
        const val FX_AUTO_KEY = 15
        const val FX_AUTO_SCALE = 16
        const val FX_AUTO_STRENGTH = 17
        const val FX_AUTO_RETUNE = 18
        const val FX_AUTO_MIX = 19
        const val FX_SWING = 20
        const val FX_NUDGE = 21
        const val FX_CHANCE = 22
        const val FX_EVERY = 23
        const val FX_WOBBLE_MODE = 24
        const val FX_WOBBLE_RATE = 25
        const val FX_WOBBLE_DEPTH = 26
        const val FX_TRIM_START = 27
        const val FX_TRIM_END = 28
        @JvmField val MENU = arrayOf("Track effects", "Add sample", "Tempo", "Sync", "Project", "System", "Check the back")
        @JvmField val EXPORT_ITEMS = arrayOf("As MIDI", "As WAV", "As Project")
        @JvmField val ADD_SAMPLE = arrayOf("Record microphone", "Record device", "Import sample")
        @JvmField val PROJECT_ITEMS = arrayOf("New", "Open", "Edit", "Export", "Import")
        @JvmField val PROJECT_EDIT_ITEMS = arrayOf("Rename", "Color", "Delete")
        @JvmField val PAGE_ACTIONS = arrayOf("Copy", "Swap", "Reset page")
        @JvmField val CASSETTE_NAMES = arrayOf(
            "Clear", "Smoke",
            "Blush", "Peach", "Butter", "Pistachio", "Seafoam", "Powder", "Lavender", "Rose",
            "Laser", "Blaze", "Volt", "Toxic", "Cyber", "Ultra", "Hot pink", "Bubblegum",
            "Brick", "Cocoa", "Beige", "Olive", "Navy", "Charcoal", "Ivory")
        @JvmField val CASSETTE_COLORS = longArrayOf(
            0xffdde4e8, 0xff7d848b,
            0xfff4b6b6, 0xfff6c9a0, 0xfff4e6a2, 0xffc9e6a8, 0xffa8e2cf, 0xffa9cdee, 0xffc7b6ee, 0xfff0b6d8,
            0xffff2e63, 0xffff7a1a, 0xffe8ff2a, 0xff39ff6a, 0xff16e8ff, 0xff6a4dff, 0xffff3ad2, 0xffff78d4,
            0xffa8483a, 0xff8a6248, 0xffe3d3b2, 0xff7d8a4a, 0xff2e3f6e, 0xff3b3d40, 0xffeee8d8).map { it.toInt() }.toIntArray()
        @JvmField val CASSETTE_ROWS = intArrayOf(2, 8, 8, 7)
        const val OPAQUE_FROM = 18
        private val RETIRED_CASSETTE_NAMES = arrayOf("Ruby" to "Laser", "Coral" to "Peach", "Tangerine" to "Blaze", "Lemon" to "Volt", "Lime" to "Toxic",
            "Mint" to "Seafoam", "Teal" to "Cyber", "Sky" to "Powder", "Ocean" to "Powder", "Grape" to "Lavender")
        private val OLD_CASSETTE_NAMES = arrayOf("Smoke", "Ruby", "Tangerine", "Lemon", "Mint", "Ocean", "Grape", "Bubblegum", "Clear", "Lime", "Teal", "Sky", "Coral", "Cocoa")
        @JvmField val FX_LABELS = arrayOf("SAMPLE SPEED", "PITCH", "REVERB", "DISTORTION", "DISTORTION MIX", "VOLUME", "HIGH PASS", "LOW PASS", "DELAY TIME", "DELAY ECHOES", "DELAY MIX", "ATTACK", "DECAY", "SUSTAIN", "RELEASE", "AUTOTUNE KEY", "AUTOTUNE SCALE", "AUTOTUNE STRENGTH", "AUTOTUNE RETUNE", "AUTOTUNE MIX", "SWING", "TIMING NUDGE", "TRIGGER CHANCE", "TRIGGER EVERY", "WOBBLE MODE", "WOBBLE RATE", "WOBBLE DEPTH", "SHIFT START", "SHIFT END")
        @JvmField val WOBBLE_MODES = arrayOf("Volume", "Pitch", "Speed")
        @JvmField val FX_NAMES = arrayOf("Speed", "Autotune", "High pass", "Delay", "Distortion", "Envelope", "Reverb", "Swing", "Volume", "Shift", "Pitch", "Low pass", "Nudge", "Chance", "Every Nth", "Wobble")
        @JvmField val FX_LOCK_REASONS = arrayOf("PRE-RENDERED INTO THE SAMPLE BEFORE PLAY", "PRE-RENDERED INTO THE SAMPLE BEFORE PLAY", null, null, null, "SHAPES EACH NOTE BEFORE NOTES ARE MIXED", null, "MOVES NOTE TIMING, NOT THE SOUND", "SETS THE TRACK'S FINAL OUTPUT LEVEL", "PICKS WHICH PART OF THE TAPE PLAYS", "PRE-RENDERED INTO THE SAMPLE BEFORE PLAY", null, "MOVES NOTE TIMING, NOT THE SOUND", "DECIDES IF A NOTE PLAYS AT ALL", "DECIDES IF A NOTE PLAYS AT ALL", "WOBBLES EACH NOTE BEFORE NOTES ARE MIXED")
        const val ALONE_REASON = "ADD FILTER, DELAY, DIST OR REVERB"
        @JvmField val FX_ADD_ORDER = intArrayOf(Sequencer.Effects.VOLUME, Sequencer.Effects.PITCH, Sequencer.Effects.SAMPLE, Sequencer.Effects.AUTOTUNE, Sequencer.Effects.SHIFT, Sequencer.Effects.HIGH_PASS, Sequencer.Effects.LOW_PASS, Sequencer.Effects.DISTORTION, Sequencer.Effects.DELAY, Sequencer.Effects.REVERB, Sequencer.Effects.ENVELOPE, Sequencer.Effects.WOBBLE, Sequencer.Effects.SWING, Sequencer.Effects.NUDGE, Sequencer.Effects.CHANCE, Sequencer.Effects.EVERY)
        @JvmField val FX_GROUP_PARAMETERS = arrayOf(intArrayOf(FX_SPEED), intArrayOf(FX_AUTO_KEY, FX_AUTO_SCALE, FX_AUTO_STRENGTH, FX_AUTO_RETUNE, FX_AUTO_MIX), intArrayOf(FX_HIGH_PASS), intArrayOf(FX_DELAY_TIME, FX_DELAY_FEEDBACK, FX_DELAY_MIX), intArrayOf(FX_DISTORTION, FX_DISTORTION_MIX), intArrayOf(FX_ATTACK, FX_DECAY, FX_SUSTAIN, FX_RELEASE), intArrayOf(FX_REVERB), intArrayOf(FX_SWING), intArrayOf(FX_VOLUME), intArrayOf(FX_TRIM_START, FX_TRIM_END), intArrayOf(FX_PITCH), intArrayOf(FX_LOW_PASS), intArrayOf(FX_NUDGE), intArrayOf(FX_CHANCE), intArrayOf(FX_EVERY), intArrayOf(FX_WOBBLE_MODE, FX_WOBBLE_RATE, FX_WOBBLE_DEPTH))
        @JvmField val FX_GROUP_LABELS = arrayOf(arrayOf("Speed"), arrayOf("Key", "Scale", "Strength", "Retune", "Mix"), arrayOf("High pass"), arrayOf("Time", "Echoes", "Mix"), arrayOf("Type", "Mix"), arrayOf("Attack", "Decay", "Sustain", "Release"), arrayOf("Mix"), arrayOf("Swing"), arrayOf("Volume"), arrayOf("Start", "End"), arrayOf("Pitch"), arrayOf("Low pass"), arrayOf("Nudge"), arrayOf("Chance"), arrayOf("Every"), arrayOf("Mode", "Rate", "Depth"))
        const val SECRET_TRANS = 0
        const val SECRET_PRIDE = 1
        const val SECRET_SWEDEN = 2
        const val SECRET_LIL_GUY = 3
        const val SECRET_COLORS = 4
        const val SECRET_SNEAKY = 5
        const val SECRETS = 6
        private val SECRET_LABELS = arrayOf("Trans flag", "Pride flag", "Swedish flag", "Lil' guy", "Case colors", "Sneaky guy")
        private val SECRET_KEYS = arrayOf("transFlag", "prideFlag", "swedenFlag", "anarchy", "caseColors", "sneakyGuy")

        @JvmStatic fun opaqueCassette(color: Int) = (OPAQUE_FROM until CASSETTE_COLORS.size).any { CASSETTE_COLORS[it] == color }

        @JvmStatic fun cassetteIndex(stored: String?, fallback: Int): Int {
            var name = stored?.trim() ?: ""
            if (name.matches(Regex("[0-9]+"))) {
                val old = name.toInt()
                name = if (old < OLD_CASSETTE_NAMES.size) OLD_CASSETTE_NAMES[old] else ""
            }
            for ((from, to) in RETIRED_CASSETTE_NAMES) if (from.equals(name, ignoreCase = true)) name = to
            val index = CASSETTE_NAMES.indexOfFirst { it.equals(name, ignoreCase = true) }
            return if (index >= 0) index else Math.floorMod(fallback, CASSETTE_NAMES.size)
        }

        @JvmStatic fun lockTitle(group: Int) = when {
            Sequencer.Effects.movable(group) -> "NOTHING TO SWAP WITH"
            group == Sequencer.Effects.VOLUME -> "ALWAYS LAST"
            else -> "ALWAYS FIRST"
        }

        private fun trackOnly(group: Int) = group == Sequencer.Effects.SWING || group == Sequencer.Effects.NUDGE

        private fun transforms(group: Int) = group == Sequencer.Effects.SHIFT || group == Sequencer.Effects.SAMPLE || group == Sequencer.Effects.PITCH || group == Sequencer.Effects.AUTOTUNE

        @JvmStatic fun ordinal(value: Int): String {
            val tens = value % 100
            val suffix = if (tens in 11..13) "th" else when (value % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
            return "$value$suffix"
        }

        @JvmStatic fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

        @JvmStatic fun halfSteps(value: Int): String {
            val text = if (value % 2 == 0) (Math.abs(value) / 2).toString() else String.format(Locale.US, "%.1f", Math.abs(value) / 2.0)
            return if (value > 0) "+$text" else if (value < 0) "-$text" else text
        }

        @JvmStatic fun seconds(frame: Long): String = String.format(Locale.US, "%.3f", frame / Tape.RATE.toDouble())
    }
}
