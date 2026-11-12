package com.claptune.player

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.claptune.storage.AppSettings
import com.claptune.storage.SongInfo
import java.io.File

/** ExoPlayer wrapper; must be created and used on the main thread. */
class SongPlayer(
    private val context: Context,
    private val onPlayingChanged: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var fadeRunnable: Runnable? = null

    val isPlaying: Boolean get() = player != null

    fun play(song: SongInfo, settings: AppSettings) {
        stop()
        if (!File(song.filePath).exists()) return

        if (settings.maxVolume) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        }

        val p = ExoPlayer.Builder(context).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        p.setHandleAudioBecomingNoisy(true)
        p.setWakeMode(C.WAKE_MODE_LOCAL)
        p.repeatMode = if (settings.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) stop()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Paused by focus loss / headphones unplugged: treat as the end of this session.
                if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) stop()
            }

            override fun onPlayerError(error: PlaybackException) = stop()
        })
        p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(song.filePath))))
        player = p

        if (settings.fadeInSec > 0) startFade(p, settings.fadeInSec) else p.volume = 1f
        p.prepare()
        p.playWhenReady = true
        onPlayingChanged(true)
    }

    private fun startFade(p: ExoPlayer, seconds: Int) {
        p.volume = 0f
        val stepMs = 100L
        val steps = seconds * 1000L / stepMs
        var i = 0L
        val r = object : Runnable {
            override fun run() {
                if (player !== p) return
                i++
                p.volume = (i.toFloat() / steps).coerceAtMost(1f)
                if (i < steps) handler.postDelayed(this, stepMs)
            }
        }
        fadeRunnable = r
        handler.postDelayed(r, stepMs)
    }

    fun stop() {
        fadeRunnable?.let(handler::removeCallbacks)
        fadeRunnable = null
        val p = player ?: return
        player = null
        p.release()
        onPlayingChanged(false)
    }
}
