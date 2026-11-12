package com.claptune.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ListenStatus { OFF, LISTENING, RETRYING }

data class ListenerSnapshot(
    val status: ListenStatus = ListenStatus.OFF,
    val level: Float = 0f,
    val threshold: Float = 0f,
    val playing: Boolean = false,
    /** Increments on every single clap, used by the UI to flash. */
    val clapCount: Int = 0,
    /** Increments every time the double clap fires. */
    val triggerCount: Int = 0,
    /** Set when there is no saved song at the moment a clap triggers. */
    val noSongNotice: Boolean = false,
)

/** In-process state shared between MicListenerService and the UI. */
object ListenerState {
    private val _state = MutableStateFlow(ListenerSnapshot())
    val state: StateFlow<ListenerSnapshot> = _state.asStateFlow()

    fun update(block: (ListenerSnapshot) -> ListenerSnapshot) = _state.update(block)

    private inline fun <T> MutableStateFlow<T>.update(f: (T) -> T) {
        while (true) {
            val prev = value
            if (compareAndSet(prev, f(prev))) return
        }
    }
}
