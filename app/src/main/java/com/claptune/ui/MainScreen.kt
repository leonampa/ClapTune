package com.claptune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.claptune.R
import com.claptune.audio.ListenStatus
import com.claptune.audio.ListenerSnapshot
import com.claptune.storage.AppSettings
import com.claptune.storage.SongInfo
import kotlin.math.roundToInt

data class SetupState(
    val mic: Boolean,
    val notifications: Boolean,
    val batteryOk: Boolean,
)

@Composable
fun MainScreen(
    vm: AppViewModel,
    setup: SetupState,
    onGrantMic: () -> Unit,
    onGrantNotifications: () -> Unit,
    onBattery: () -> Unit,
) {
    val song by vm.song.collectAsState()
    val settings by vm.settings.collectAsState()
    val listener by vm.listener.collectAsState()
    val ready = setup.mic

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        if (!setup.mic || !setup.notifications || !setup.batteryOk) {
            SetupCard(setup, onGrantMic, onGrantNotifications, onBattery)
        }

        ListeningCard(listener, settings.listening || listener.status != ListenStatus.OFF, ready) {
            vm.setListening(it)
        }

        SongCard(song, listener, onChange = { vm.screen = Screen.SEARCH }, onTest = vm::testPlay, onStop = vm::stopMusic, canPlay = ready)

        CalibrationCard(listener, settings) { k -> vm.updateSettings { it.copy(sensitivityK = k) } }

        SettingsCard(settings, vm::updateSettings)

        Text(stringResource(R.string.footer_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SetupCard(setup: SetupState, onMic: () -> Unit, onNotif: () -> Unit, onBattery: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.setup_plugged), style = MaterialTheme.typography.bodyMedium)
            if (!setup.mic) SetupRow(stringResource(R.string.setup_mic), onMic)
            if (!setup.notifications) SetupRow(stringResource(R.string.setup_notifications), onNotif)
            if (!setup.batteryOk) SetupRow(stringResource(R.string.setup_battery), onBattery)
        }
    }
}

@Composable
private fun SetupRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick) { Text(stringResource(R.string.setup_grant)) }
    }
}

@Composable
private fun ListeningCard(l: ListenerSnapshot, on: Boolean, ready: Boolean, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.listen_toggle), style = MaterialTheme.typography.titleLarge)
                val status = when {
                    !ready -> R.string.status_missing_permission
                    l.status == ListenStatus.LISTENING -> if (l.playing) R.string.status_playing else R.string.status_listening
                    l.status == ListenStatus.RETRYING -> R.string.status_retrying
                    else -> R.string.status_off
                }
                Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = on && ready, enabled = ready, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SongCard(
    song: SongInfo?,
    l: ListenerSnapshot,
    onChange: () -> Unit,
    onTest: () -> Unit,
    onStop: () -> Unit,
    canPlay: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.song_title), style = MaterialTheme.typography.titleMedium)
            if (song == null) {
                Text(stringResource(R.string.song_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text("${song.channel} · ${formatDuration(song.durationSec)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (l.noSongNotice) {
                Text(stringResource(R.string.song_missing_notice), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onChange) {
                    Icon(Icons.Filled.Search, null)
                    Text(stringResource(R.string.song_change), Modifier.padding(start = 6.dp))
                }
                if (l.playing) {
                    OutlinedButton(onStop) {
                        Icon(Icons.Filled.Close, null)
                        Text(stringResource(R.string.song_stop), Modifier.padding(start = 6.dp))
                    }
                } else {
                    OutlinedButton(onTest, enabled = song != null && canPlay) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text(stringResource(R.string.song_test), Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationCard(l: ListenerSnapshot, s: AppSettings, onSensitivity: (Float) -> Unit) {
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(l.clapCount) {
        if (l.clapCount > 0) {
            flash = true
            kotlinx.coroutines.delay(400)
            flash = false
        }
    }
    val listening = l.status == ListenStatus.LISTENING
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.calib_title), style = MaterialTheme.typography.titleMedium)
            if (!listening) {
                Text(stringResource(R.string.calib_hint), style = MaterialTheme.typography.bodySmall)
            }
            // Meter: the vertical line marks the trigger threshold.
            val scale = 1.5f
            val fraction = if (listening && l.threshold > 0f) (l.level / (l.threshold * scale)).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(if (fraction >= 1f / scale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                )
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f / scale))
                    Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
                    Box(Modifier.weight(1f - 1f / scale))
                }
            }
            Text(
                if (flash) stringResource(R.string.calib_clap) else " ",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.calib_sensitivity), style = MaterialTheme.typography.bodyMedium)
            // Right = more sensitive = lower K.
            Slider(
                value = (K_MAX - s.sensitivityK) / (K_MAX - K_MIN),
                onValueChange = { onSensitivity(K_MAX - it * (K_MAX - K_MIN)) },
            )
        }
    }
}

@Composable
private fun SettingsCard(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)

            IntSlider(R.string.set_min_gap, s.minGapMs, 80..500, "ms") { v -> update { it.copy(minGapMs = v, maxGapMs = maxOf(it.maxGapMs, v + 100)) } }
            IntSlider(R.string.set_max_gap, s.maxGapMs, 300..1500, "ms") { v -> update { it.copy(maxGapMs = v, minGapMs = minOf(it.minGapMs, v - 100)) } }
            IntSlider(R.string.set_cooldown, s.cooldownSec, 0..60, "s") { v -> update { it.copy(cooldownSec = v) } }
            IntSlider(R.string.set_fade, s.fadeInSec, 0..30, "s") { v -> update { it.copy(fadeInSec = v) } }

            SwitchRow(R.string.set_triple, s.tripleClap) { v -> update { it.copy(tripleClap = v) } }
            SwitchRow(R.string.set_loop, s.loop) { v -> update { it.copy(loop = v) } }
            SwitchRow(R.string.set_max_volume, s.maxVolume) { v -> update { it.copy(maxVolume = v) } }
        }
    }
}

@Composable
private fun IntSlider(label: Int, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Text("${stringResource(label)}: $value $unit", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

fun formatDuration(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

private const val K_MIN = 3f
private const val K_MAX = 16f
