package com.tapedecksampler.app

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.IOException

@TargetApi(29)
class DeviceCaptureService : Service() {
    @Volatile private var stopping = false
    private var thread: Thread? = null
    private var projection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopping = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == STOP) {
            stopping = true
            if (thread == null) stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT < 29) { stopSelf(); return START_NOT_STICKY }
        frames = 0
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("deviceCapture", "Device audio recording", NotificationManager.IMPORTANCE_LOW))
            val flagsImmutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flagsImmutable)
            val stop = PendingIntent.getService(this, 1, Intent(this, DeviceCaptureService::class.java).setAction(STOP), flagsImmutable)
            val notification = Notification.Builder(this, "deviceCapture").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Recording device audio").setContentText("Switch apps to capture audio. Stop when finished.")
                .setContentIntent(open).setOngoing(true).setUsesChronometer(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val consent: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("consent", Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra("consent")
            if (consent == null) throw IOException("Recording permission was cancelled")
            val started = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("result", 0), consent)
                ?: throw IOException("Device audio capture unavailable")
            projection = started
            started.registerCallback(projectionCallback, handler)
            stopping = false; frames = 0; active = true
            consumed(this)
            notifyChanged()
            thread = Thread(::record, "sampler-device-capture").also { it.start() }
        } catch (e: Exception) { finishCapture(message(e)) }
        return START_NOT_STICKY
    }

    private fun record() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var input: AudioRecord? = null
        var error = ""
        try {
            CaptureFile(path(this)).use { output ->
                val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build()
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Tape.RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
                val minimum = AudioRecord.getMinBufferSize(Tape.RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val record = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(MIN_BUFFER_BYTES, minimum)).setAudioPlaybackCaptureConfig(config).build()
                input = record
                if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("Device audio capture unavailable")
                record.startRecording()
                val stereo = ShortArray(BLOCK_FRAMES * 2)
                val mono = ShortArray(BLOCK_FRAMES)
                var nextUpdate = 0L
                while (!stopping && output.frames() < Tape.LENGTH) {
                    val count = record.read(stereo, 0, stereo.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) throw IOException("Device audio capture disconnected")
                    for (i in 0 until count / 2) mono[i] = ((stereo[i * 2] + stereo[i * 2 + 1]) / 2).toShort()
                    output.write(mono, count / 2)
                    frames = output.frames()
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextUpdate) { notifyChanged(); nextUpdate = now + UPDATE_INTERVAL_MS }
                }
            }
        } catch (e: SecurityException) { error = "Device audio recording permission was withdrawn" }
        catch (e: Exception) { error = message(e) }
        finally {
            input?.let {
                try { it.stop() } catch (ignored: IllegalStateException) {}
                it.release()
            }
        }
        val result = error
        handler.post { finishCapture(result) }
    }

    private fun notifyChanged() = sendBroadcast(Intent(CHANGED).setPackage(packageName))

    private fun finishCapture(error: String) {
        active = false
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        state(this).edit().putBoolean("ready", frames >= MIN_FRAMES && path(this).length() == frames.toLong() * Tape.BYTES_PER_SAMPLE + Tape.HEADER_BYTES)
            .putString("error", if (error.isEmpty() && frames < MIN_FRAMES) "No audio recorded" else error).commit()
        notifyChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopping = true; super.onDestroy() }

    companion object {
        const val CHANGED = "com.tapedecksampler.app.DEVICE_CAPTURE_CHANGED"
        const val STOP = "com.tapedecksampler.app.STOP_DEVICE_CAPTURE"
        private const val NOTIFICATION_ID = 40
        private const val MIN_BUFFER_BYTES = 16384
        private const val BLOCK_FRAMES = 2048
        private const val UPDATE_INTERVAL_MS = 250
        private const val MIN_FRAMES = 48
        @Volatile private var active = false
        @Volatile private var frames = 0

        private fun state(context: Context): SharedPreferences = context.getSharedPreferences("deviceCapture", MODE_PRIVATE)
        private fun message(error: Exception) = error.message ?: "Device audio recording failed"

        @JvmStatic fun active(): Boolean = active
        @JvmStatic fun frames(): Int = frames
        @JvmStatic fun path(context: Context) = File(context.filesDir, "device-capture.wav")
        @JvmStatic fun error(context: Context): String = state(context).getString("error", "") ?: ""
        @JvmStatic fun pending(context: Context): Boolean = state(context).getBoolean("ready", false)
        @JvmStatic fun consumed(context: Context) = state(context).edit().putBoolean("ready", false).putString("error", "").apply()
        @JvmStatic fun stop(context: Context) { if (active) context.startService(Intent(context, DeviceCaptureService::class.java).setAction(STOP)) }
    }
}
