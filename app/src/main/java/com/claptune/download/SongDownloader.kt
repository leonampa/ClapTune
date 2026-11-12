package com.claptune.download

import android.content.Context
import com.claptune.search.NewPipeDownloader
import com.claptune.search.YouTubeRepository
import com.claptune.storage.SongInfo
import com.claptune.storage.SongStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads the audio of a YouTube video into filesDir/song and records it as the current song.
 * The previous song is only replaced after the new file is completely written.
 */
class SongDownloader(
    context: Context,
    private val repo: YouTubeRepository,
    private val store: SongStore,
) {
    private val dir = File(context.applicationContext.filesDir, "song").apply { mkdirs() }

    suspend fun download(url: String, onProgress: (Float) -> Unit): SongInfo = withContext(Dispatchers.IO) {
        val audio = repo.resolve(url)
        val temp = File(dir, "download.part")
        try {
            fetch(audio.streamUrl, temp, onProgress)

            val target = File(dir, "current.${audio.extension}")
            val old = store.currentSong()?.file
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IOException("Could not move downloaded file")

            val song = SongInfo(
                videoId = audio.videoId,
                title = audio.title,
                channel = audio.channel,
                thumbnailUrl = audio.thumbnailUrl,
                filePath = target.absolutePath,
                durationSec = audio.durationSec,
            )
            store.saveSong(song)
            if (old != null && old.absolutePath != target.absolutePath) old.delete()
            song
        } finally {
            temp.delete()
        }
    }

    /** Downloads in ranged chunks; YouTube throttles or rejects single huge requests. */
    private suspend fun fetch(url: String, out: File, onProgress: (Float) -> Unit) {
        val client = repo.downloader.client
        var offset = 0L
        var total = -1L
        out.outputStream().use { sink ->
            while (total < 0 || offset < total) {
                currentCoroutineContext().ensureActive()
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", NewPipeDownloader.USER_AGENT)
                    .header("Range", "bytes=$offset-${offset + CHUNK - 1}")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty body")
                    if (resp.code == 200) {
                        // Server ignored the range: it is sending the entire file.
                        total = body.contentLength()
                        if (offset != 0L) throw IOException("Server does not support ranges")
                    } else {
                        total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: throw IOException("Missing Content-Range")
                    }
                    val buf = ByteArray(32 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            offset += n
                            if (total > 0) onProgress((offset.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (total <= 0) break
            }
        }
        if (total > 0 && offset < total) throw IOException("Incomplete download")
    }

    private companion object {
        const val CHUNK = 2L * 1024 * 1024
    }
}
