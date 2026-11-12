package com.claptune.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.util.Locale

data class SearchHit(
    val url: String,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val thumbnailUrl: String,
)

data class ResolvedAudio(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSec: Long,
    val thumbnailUrl: String,
    val streamUrl: String,
    val extension: String,
)

class YouTubeRepository(val downloader: NewPipeDownloader = NewPipeDownloader.create()) {

    init {
        NewPipe.init(downloader, Localization("en", "US"))
    }

    suspend fun search(query: String, limit: Int = 5): List<SearchHit> = withContext(Dispatchers.IO) {
        val extractor = ServiceList.YouTube.getSearchExtractor(
            query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "",
        )
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM && it.duration > 0 }
            .take(limit)
            .map {
                SearchHit(
                    url = it.url,
                    title = it.name,
                    channel = it.uploaderName.orEmpty(),
                    durationSec = it.duration,
                    thumbnailUrl = it.thumbnails.firstOrNull()?.url.orEmpty(),
                )
            }
    }

    /** Resolves a watch URL to the best audio-only progressive stream. */
    suspend fun resolve(url: String): ResolvedAudio = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val stream = pickAudio(info.audioStreams)
            ?: throw NoAudioStreamException()
        ResolvedAudio(
            videoId = info.id,
            title = info.name,
            channel = info.uploaderName.orEmpty(),
            durationSec = info.duration,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url.orEmpty(),
            streamUrl = stream.content,
            extension = stream.format?.suffix ?: "m4a",
        )
    }

    private fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val bitrate = { s: AudioStream -> s.averageBitrate }
        return usable.filter { it.format == MediaFormat.M4A }.maxByOrNull(bitrate)
            ?: usable.maxByOrNull(bitrate)
    }

    companion object {
        private val LINK = Regex("""^(https?://)?([\w-]+\.)?(youtube\.com|youtu\.be|youtube-nocookie\.com)/\S+""", RegexOption.IGNORE_CASE)

        /** Finds the first YouTube link in arbitrary text (e.g. a share intent). */
        fun extractLink(text: String): String? =
            text.split(Regex("\\s+")).map { it.trim() }.firstOrNull { LINK.matches(it) }
                ?.let { if (it.startsWith("http", true)) it else "https://$it" }
    }
}

class NoAudioStreamException : Exception("No audio stream available")
