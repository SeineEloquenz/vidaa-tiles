package nz.eloque.vidaa_tiles.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.eloque.vidaa_tiles.tiles.TileSpec
import nz.eloque.vidaa_tiles.tv.Tile
import nz.eloque.vidaa_tiles.tv.TvClient
import nz.eloque.vidaa_tiles.tv.TvInfo

sealed interface Pairing {
    data object Idle : Pairing

    data class Connecting(
        val host: String,
    ) : Pairing

    data class AwaitingPin(
        val host: String,
    ) : Pairing
}

data class UiState(
    val pairedHost: String? = null,
    val discovered: List<TvInfo> = emptyList(),
    val searching: Boolean = false,
    val pairing: Pairing = Pairing.Idle,
    val busy: Boolean = false,
    val tiles: List<Tile> = emptyList(),
    val message: String? = null,
)

class TilesViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val client = TvClient(application)
    private val _state = MutableStateFlow(UiState(pairedHost = client.currentSession()?.host))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pin: CompletableDeferred<String>? = null

    fun search() =
        launchCatching {
            _state.update { it.copy(searching = true, discovered = emptyList()) }
            try {
                val found = client.discover()
                _state.update {
                    it.copy(
                        discovered = found,
                        message = if (found.isEmpty()) "No VIDAA TV found. Enter its IP address instead." else null,
                    )
                }
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }

    fun pair(host: String) =
        launchCatching {
            _state.update { it.copy(pairing = Pairing.Connecting(host)) }
            try {
                val session =
                    client.pair(host.trim()) {
                        val deferred = CompletableDeferred<String>().also { pin = it }
                        _state.update { it.copy(pairing = Pairing.AwaitingPin(host)) }
                        deferred.await()
                    }
                _state.update { it.copy(pairedHost = session.host, message = "Paired with ${session.host}") }
            } finally {
                pin = null
                _state.update { it.copy(pairing = Pairing.Idle) }
            }
        }

    fun submitPin(value: String) {
        pin?.complete(value)
    }

    fun cancelPairing() {
        pin?.cancel()
    }

    fun forget() {
        client.forget()
        _state.update { UiState() }
    }

    fun refreshTiles() =
        busy {
            val tiles = client.listTiles()
            _state.update { it.copy(tiles = tiles) }
        }

    fun install(spec: TileSpec) =
        busy {
            val tiles = client.addTile(spec.appId, spec.name, spec.url, spec.image)
            val installed = tiles.any { it.appId.equals(spec.appId, ignoreCase = true) }
            _state.update {
                it.copy(
                    tiles = tiles,
                    message =
                        if (installed) {
                            "${spec.name} added to the TV launcher"
                        } else {
                            "Sent ${spec.name} to the TV, but it is not in the app list yet"
                        },
                )
            }
        }

    fun launch(tile: Tile) = busy { client.launchTile(tile.appId, tile.name, tile.url) }

    fun showError(message: String) = _state.update { it.copy(message = message) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun busy(block: suspend CoroutineScope.() -> Unit) =
        launchCatching {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }

    private fun launchCatching(block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: e.javaClass.simpleName) }
            }
        }
}
