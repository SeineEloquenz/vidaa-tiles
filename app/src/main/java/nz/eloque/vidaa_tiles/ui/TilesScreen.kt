package nz.eloque.vidaa_tiles.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.eloque.vidaa_tiles.R
import nz.eloque.vidaa_tiles.tiles.Preset
import nz.eloque.vidaa_tiles.tiles.Presets
import nz.eloque.vidaa_tiles.tiles.ServerInput
import nz.eloque.vidaa_tiles.tiles.TileSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TilesScreen(viewModel: TilesViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    LaunchedEffect(state.pairedHost) {
        if (state.pairedHost != null) viewModel.refreshTiles()
    }

    fun tryInstall(build: () -> TileSpec) {
        runCatching(build).onSuccess(viewModel::install).onFailure { viewModel.showError(it.message.orEmpty()) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { TvCard(state, viewModel) }
            if (state.pairedHost != null) {
                items(
                    Preset.entries,
                ) { preset -> PresetCard(preset, enabled = !state.busy) { server -> tryInstall { preset.build(server) } } }
                item { CustomTileCard(enabled = !state.busy) { name, url, image -> tryInstall { Presets.custom(name, url, image) } } }
                item { InstalledTiles(state, viewModel) }
            }
        }
    }

    (state.pairing as? Pairing.AwaitingPin)?.let { PinDialog(it.host, viewModel::submitPin, viewModel::cancelPairing) }
}

@Composable
private fun TvCard(
    state: UiState,
    viewModel: TilesViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tv), style = MaterialTheme.typography.titleMedium)
            val paired = state.pairedHost
            if (paired != null) {
                Text(stringResource(R.string.paired_with, paired))
                OutlinedButton(onClick = viewModel::forget) { Text(stringResource(R.string.forget)) }
                return@Column
            }
            val pairing = state.pairing
            if (pairing is Pairing.Connecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.connecting, pairing.host))
                }
                return@Column
            }
            Button(onClick = viewModel::search, enabled = !state.searching) {
                Text(stringResource(if (state.searching) R.string.searching else R.string.search))
            }
            state.discovered.forEach { tv ->
                ListItem(
                    headlineContent = { Text(tv.name) },
                    supportingContent = { Text(tv.host) },
                    leadingContent = { Icon(Icons.Default.Tv, null) },
                    trailingContent = { TextButton(onClick = { viewModel.pair(tv.host) }) { Text(stringResource(R.string.pair)) } },
                )
            }
            var host by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.tv_ip)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { viewModel.pair(host) }, enabled = host.isNotBlank()) { Text(stringResource(R.string.pair)) }
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: Preset,
    enabled: Boolean,
    onInstall: (String) -> Unit,
) {
    var server by rememberSaveable(preset.name) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(preset.title, style = MaterialTheme.typography.titleMedium)
            Text(preset.description, style = MaterialTheme.typography.bodyMedium)
            if (preset.server != ServerInput.NONE) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = {
                        Text(stringResource(if (preset.server == ServerInput.REQUIRED) R.string.server else R.string.server_optional))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = { onInstall(server) },
                enabled = enabled && (preset.server != ServerInput.REQUIRED || server.isNotBlank()),
            ) { Text(stringResource(R.string.install)) }
        }
    }
}

@Composable
private fun CustomTileCard(
    enabled: Boolean,
    onInstall: (String, String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var image by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.custom_tile), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                name,
                { name = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
            )
            OutlinedTextField(
                url,
                { url = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                image,
                { image = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.image_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Button(onClick = { onInstall(name, url, image) }, enabled = enabled && name.isNotBlank() && url.isNotBlank()) {
                Text(stringResource(R.string.install))
            }
        }
    }
}

@Composable
private fun InstalledTiles(
    state: UiState,
    viewModel: TilesViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.installed_tiles), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::refreshTiles, enabled = !state.busy) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
            }
            if (state.tiles.isEmpty()) Text(stringResource(R.string.no_tiles), style = MaterialTheme.typography.bodyMedium)
            state.tiles.forEach { tile ->
                ListItem(
                    headlineContent = { Text(tile.name.ifEmpty { tile.appId }) },
                    supportingContent = { Text(tile.url, maxLines = 1) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.launch(tile) }, enabled = !state.busy && tile.url.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.launch))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PinDialog(
    host: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.enter_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pin_hint, host))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter(Char::isDigit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pin) }, enabled = pin.isNotEmpty()) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
