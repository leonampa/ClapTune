package com.claptune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.claptune.R
import com.claptune.search.SearchHit

@Composable
fun SearchScreen(vm: AppViewModel) {
    val busy = vm.download is DownloadUi.Running
    BackHandler { if (!busy) vm.screen = Screen.MAIN }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.screen = Screen.MAIN }, enabled = !busy) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineSmall)
        }

        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.search_hint)) },
            trailingIcon = {
                IconButton(vm::search) { Icon(Icons.Filled.Search, stringResource(R.string.search_go)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
        )

        if (vm.searching) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        vm.searchError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        if (vm.searchedOnce && vm.results.isEmpty() && !vm.searching && vm.searchError == null) {
            Text(stringResource(R.string.search_empty))
        }
        vm.results.forEach { hit -> ResultCard(hit) { vm.pending = PendingSong(hit.url, hit.title) } }

        Text(stringResource(R.string.link_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = vm.link,
            onValueChange = { vm.link = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.link_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.submitLink() }),
        )
        Button(vm::submitLink, enabled = vm.link.isNotBlank() && !busy) { Text(stringResource(R.string.link_use)) }
    }

    vm.pending?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.pending = null },
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(p.title ?: p.url) },
            confirmButton = { TextButton(vm::confirmPending) { Text(stringResource(R.string.confirm_yes)) } },
            dismissButton = { TextButton({ vm.pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    when (val d = vm.download) {
        is DownloadUi.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.download_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.title?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (d.progress <= 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(vm::cancelDownload) { Text(stringResource(R.string.cancel)) } },
        )
        is DownloadUi.Failed -> AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text(stringResource(R.string.download_failed)) },
            text = { Text(stringResource(d.message)) },
            confirmButton = { TextButton(vm::dismissError) { Text(stringResource(R.string.ok)) } },
        )
        DownloadUi.Idle -> {}
    }
}

@Composable
private fun ResultCard(hit: SearchHit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = hit.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(120.dp, 68.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(hit.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text("${hit.channel} · ${formatDuration(hit.durationSec)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
