package com.claptune.ui

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claptune.R
import com.claptune.audio.ListenerState
import com.claptune.audio.MicListenerService
import com.claptune.download.SongDownloader
import com.claptune.search.NoAudioStreamException
import com.claptune.search.SearchHit
import com.claptune.search.YouTubeRepository
import com.claptune.storage.AppSettings
import com.claptune.storage.SongStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import java.io.IOException

enum class Screen { MAIN, SEARCH }

/** What the user tapped and is being asked to confirm. [title] is null for pasted/shared links. */
data class PendingSong(val url: String, val title: String?)

sealed interface DownloadUi {
    data object Idle : DownloadUi
    data class Running(val title: String?, val progress: Float) : DownloadUi
    data class Failed(@StringRes val message: Int) : DownloadUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SongStore.get(app)
    private val repo by lazy { YouTubeRepository() }
    private val downloader by lazy { SongDownloader(app, repo, store) }

    val song = store.song.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val listener = ListenerState.state

    var screen by mutableStateOf(Screen.MAIN)
    var pending by mutableStateOf<PendingSong?>(null)

    var query by mutableStateOf("")
    var link by mutableStateOf("")
    var searching by mutableStateOf(false)
    var results by mutableStateOf<List<SearchHit>>(emptyList())
    var searchedOnce by mutableStateOf(false)
    var searchError by mutableStateOf<Int?>(null)
    var download by mutableStateOf<DownloadUi>(DownloadUi.Idle)

    private var downloadJob: Job? = null

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        searchError = null
        viewModelScope.launch {
            try {
                results = repo.search(q)
                searchedOnce = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                results = emptyList()
                searchError = errorFor(e)
            } finally {
                searching = false
            }
        }
    }

    fun submitLink() {
        val url = YouTubeRepository.extractLink(link)
        if (url == null) {
            download = DownloadUi.Failed(R.string.err_bad_link)
            return
        }
        download = DownloadUi.Idle
        pending = PendingSong(url, null)
    }

    /** Called with text from a share intent. */
    fun handleShared(text: String?) {
        val url = text?.let { YouTubeRepository.extractLink(it) } ?: return
        screen = Screen.SEARCH
        download = DownloadUi.Idle
        pending = PendingSong(url, null)
    }

    fun confirmPending() {
        val p = pending ?: return
        pending = null
        startDownload(p)
    }

    private fun startDownload(p: PendingSong) {
        downloadJob?.cancel()
        download = DownloadUi.Running(p.title, 0f)
        downloadJob = viewModelScope.launch {
            try {
                downloader.download(p.url) { progress -> download = DownloadUi.Running(p.title, progress) }
                download = DownloadUi.Idle
                link = ""
                screen = Screen.MAIN
            } catch (e: CancellationException) {
                download = DownloadUi.Idle
                throw e
            } catch (e: Throwable) {
                // The previously saved song is untouched on any failure.
                download = DownloadUi.Failed(errorFor(e))
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun dismissError() {
        if (download is DownloadUi.Failed) download = DownloadUi.Idle
    }

    @StringRes
    private fun errorFor(e: Throwable): Int = when (e.also { Log.e("ClapTune", "Request failed", it) }) {
        is IOException -> R.string.err_network
        is NoAudioStreamException -> R.string.err_no_audio
        is ContentNotAvailableException -> R.string.err_unavailable
        is ExtractionException -> R.string.err_extractor
        else -> R.string.err_extractor
    }

    fun setListening(on: Boolean) {
        val ctx = getApplication<Application>()
        if (on) {
            MicListenerService.send(ctx, MicListenerService.ACTION_START)
        } else {
            MicListenerService.send(ctx, MicListenerService.ACTION_STOP)
        }
    }

    fun testPlay() = MicListenerService.send(getApplication(), MicListenerService.ACTION_PLAY)

    fun stopMusic() = MicListenerService.send(getApplication(), MicListenerService.ACTION_STOP_MUSIC)

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.update(block) }
    }
}
