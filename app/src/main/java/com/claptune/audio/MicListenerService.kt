package com.claptune.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.claptune.MainActivity
import com.claptune.R
import com.claptune.player.SongPlayer
import com.claptune.storage.AppSettings
import com.claptune.storage.SongStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MicListenerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recordJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var store: SongStore
    private lateinit var player: SongPlayer
    private val detector = ClapDetector(SAMPLE_RATE)

    @Volatile private var settings = AppSettings()
    @Volatile private var playing = false

    override fun onCreate() {
        super.onCreate()
        store = SongStore.get(this)
        player = SongPlayer(this) { isPlaying ->
            playing = isPlaying
            ListenerState.update { it.copy(playing = isPlaying) }
            refreshNotification()
        }
        createChannel()
        scope.launch {
            store.settings.collect {
                settings = it
                detector.config = it.toClapConfig()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                return START_NOT_STICKY
            }
            ACTION_STOP_MUSIC -> {
                player.stop()
                return START_STICKY
            }
            ACTION_PLAY -> {
                enterForeground()
                startListeningLoop()
                scope.launch { playSong() }
                return START_STICKY
            }
        }
        // ACTION_START, or null when the system restarts a sticky service.
        if (intent == null && !runBlocking { store.currentSettings().listening }) {
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground()
        startListeningLoop()
        return START_STICKY
    }

    private fun enterForeground() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                startForeground(NOTIF_ID, n, type)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Android 12+ refuses background starts; the user can reopen the app to resume.
            stopSelf()
        }
    }

    private fun startListeningLoop() {
        if (recordJob?.isActive == true) return
        acquireWakeLock()
        CoroutineScope(Dispatchers.IO).launch { store.update { it.copy(listening = true) } }
        ListenerState.update { it.copy(status = ListenStatus.LISTENING) }
        recordJob = scope.launch(Dispatchers.Default) { recordLoop() }
    }

    private suspend fun recordLoop() {
        var backoffMs = 1000L
        while (scope.isActive) {
            val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val record = if (hasPermission) createRecorder() else null
            if (record == null) {
                setStatus(ListenStatus.RETRYING)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                continue
            }
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("mic busy")
                setStatus(ListenStatus.LISTENING)
                backoffMs = 1000L
                readFrames(record)
            } catch (e: Exception) {
                setStatus(ListenStatus.RETRYING)
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun readFrames(record: AudioRecord) {
        val buf = ShortArray(FRAME_SAMPLES)
        var wasPlaying = false
        var clapCount = 0
        var triggerCount = 0
        while (scope.isActive) {
            val n = record.read(buf, 0, buf.size)
            if (n < 0) throw IllegalStateException("read error $n")
            if (n == 0) continue

            // The mic hears the song itself, so detection pauses while it plays.
            if (playing) {
                wasPlaying = true
                continue
            }
            if (wasPlaying) {
                detector.reset()
                wasPlaying = false
            }

            val r = detector.process(buf, n)
            if (r.clap) clapCount++
            if (r.triggered) triggerCount++
            val trig = r.triggered
            ListenerState.update {
                it.copy(
                    level = r.level, threshold = r.threshold, clapCount = clapCount, triggerCount = triggerCount,
                )
            }
            if (trig) scope.launch { playSong() }
        }
    }

    private fun createRecorder(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        for (source in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
            try {
                val r = AudioRecord(
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, FRAME_SAMPLES * 2 * 4),
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun playSong() {
        val song = store.currentSong()
        if (song == null) {
            ListenerState.update { it.copy(noSongNotice = true) }
            return
        }
        ListenerState.update { it.copy(noSongNotice = false) }
        player.play(song, store.currentSettings())
    }

    private fun setStatus(s: ListenStatus) {
        ListenerState.update { it.copy(status = s) }
        refreshNotification()
    }

    private fun stopListening() {
        CoroutineScope(Dispatchers.IO).launch { store.update { it.copy(listening = false) } }
        player.stop()
        recordJob?.cancel()
        releaseWakeLock()
        ListenerState.update { ListenerSnapshot() }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClapTune:listen").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun refreshNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val retrying = ListenerState.state.value.status == ListenStatus.RETRYING
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(if (retrying) R.string.notif_retrying else if (playing) R.string.notif_playing else R.string.notif_listening))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.action_stop_listening), actionIntent(ACTION_STOP, 1))
        if (playing) b.addAction(0, getString(R.string.action_stop_music), actionIntent(ACTION_STOP_MUSIC, 2))
        return b.build()
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, MicListenerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        player.stop()
        releaseWakeLock()
        ListenerState.update { ListenerSnapshot() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.claptune.START"
        const val ACTION_STOP = "com.claptune.STOP"
        const val ACTION_STOP_MUSIC = "com.claptune.STOP_MUSIC"
        const val ACTION_PLAY = "com.claptune.PLAY"
        private const val CHANNEL_ID = "listening"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 320 // 20 ms

        fun send(context: Context, action: String) {
            val i = Intent(context, MicListenerService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26 && action != ACTION_STOP && action != ACTION_STOP_MUSIC) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
