package com.claptune.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.claptune.audio.ClapConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

data class SongInfo(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String,
    val filePath: String,
    val durationSec: Long,
) {
    val file: File get() = File(filePath)
}

data class AppSettings(
    val sensitivityK: Float = 8f,
    val minGapMs: Int = 150,
    val maxGapMs: Int = 800,
    val cooldownSec: Int = 10,
    val tripleClap: Boolean = false,
    val fadeInSec: Int = 0,
    val loop: Boolean = false,
    val maxVolume: Boolean = false,
    val listening: Boolean = false,
) {
    fun toClapConfig() = ClapConfig(
        sensitivityK = sensitivityK,
        minGapMs = minGapMs,
        maxGapMs = maxGapMs,
        cooldownMs = cooldownSec * 1000,
        clapsRequired = if (tripleClap) 3 else 2,
    )
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("claptune")

class SongStore(context: Context) {
    private val store = context.applicationContext.dataStore

    private object K {
        val videoId = stringPreferencesKey("videoId")
        val title = stringPreferencesKey("title")
        val channel = stringPreferencesKey("channel")
        val thumb = stringPreferencesKey("thumb")
        val path = stringPreferencesKey("path")
        val duration = longPreferencesKey("duration")
        val sensitivity = floatPreferencesKey("sensitivityK")
        val minGap = intPreferencesKey("minGap")
        val maxGap = intPreferencesKey("maxGap")
        val cooldown = intPreferencesKey("cooldown")
        val triple = booleanPreferencesKey("triple")
        val fade = intPreferencesKey("fade")
        val loop = booleanPreferencesKey("loop")
        val maxVol = booleanPreferencesKey("maxVol")
        val listening = booleanPreferencesKey("listening")
    }

    val song: Flow<SongInfo?> = store.data.map { p ->
        val path = p[K.path] ?: return@map null
        if (!File(path).exists()) return@map null
        SongInfo(
            videoId = p[K.videoId].orEmpty(),
            title = p[K.title].orEmpty(),
            channel = p[K.channel].orEmpty(),
            thumbnailUrl = p[K.thumb].orEmpty(),
            filePath = path,
            durationSec = p[K.duration] ?: 0L,
        )
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            sensitivityK = p[K.sensitivity] ?: d.sensitivityK,
            minGapMs = p[K.minGap] ?: d.minGapMs,
            maxGapMs = p[K.maxGap] ?: d.maxGapMs,
            cooldownSec = p[K.cooldown] ?: d.cooldownSec,
            tripleClap = p[K.triple] ?: d.tripleClap,
            fadeInSec = p[K.fade] ?: d.fadeInSec,
            loop = p[K.loop] ?: d.loop,
            maxVolume = p[K.maxVol] ?: d.maxVolume,
            listening = p[K.listening] ?: d.listening,
        )
    }

    suspend fun currentSong(): SongInfo? = song.first()
    suspend fun currentSettings(): AppSettings = settings.first()

    suspend fun saveSong(s: SongInfo) = store.edit {
        it[K.videoId] = s.videoId
        it[K.title] = s.title
        it[K.channel] = s.channel
        it[K.thumb] = s.thumbnailUrl
        it[K.path] = s.filePath
        it[K.duration] = s.durationSec
    }

    suspend fun update(block: (AppSettings) -> AppSettings) = store.edit { p ->
        val n = block(settingsOf(p))
        p[K.sensitivity] = n.sensitivityK
        p[K.minGap] = n.minGapMs
        p[K.maxGap] = n.maxGapMs
        p[K.cooldown] = n.cooldownSec
        p[K.triple] = n.tripleClap
        p[K.fade] = n.fadeInSec
        p[K.loop] = n.loop
        p[K.maxVol] = n.maxVolume
        p[K.listening] = n.listening
    }

    private fun settingsOf(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            p[K.sensitivity] ?: d.sensitivityK, p[K.minGap] ?: d.minGapMs, p[K.maxGap] ?: d.maxGapMs,
            p[K.cooldown] ?: d.cooldownSec, p[K.triple] ?: d.tripleClap, p[K.fade] ?: d.fadeInSec,
            p[K.loop] ?: d.loop, p[K.maxVol] ?: d.maxVolume, p[K.listening] ?: d.listening,
        )
    }

    companion object {
        @Volatile private var instance: SongStore? = null
        fun get(context: Context): SongStore =
            instance ?: synchronized(this) { instance ?: SongStore(context).also { instance = it } }
    }
}
