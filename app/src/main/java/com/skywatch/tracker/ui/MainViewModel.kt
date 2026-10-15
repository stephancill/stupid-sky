package com.skywatch.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skywatch.tracker.alerts.AlertWorker
import com.skywatch.tracker.data.Aircraft
import com.skywatch.tracker.data.Enrichment
import com.skywatch.tracker.data.FlightData
import com.skywatch.tracker.data.Metar
import com.skywatch.tracker.data.Provider
import com.skywatch.tracker.data.Settings
import com.skywatch.tracker.data.WatchEntry
import com.skywatch.tracker.data.WatchKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Mode { ALL, MILITARY }

data class Viewport(
    val lat: Double, val lon: Double, val radiusNm: Int,
    val south: Double, val west: Double, val north: Double, val east: Double,
)

data class LatLon(val lat: Double, val lon: Double, val alt: Int?)

data class UiState(
    val mode: Mode = Mode.ALL,
    val aircraft: List<Aircraft> = emptyList(),
    val selected: Aircraft? = null,
    val enrichment: Enrichment? = null,
    val selectedTrail: List<LatLon> = emptyList(),
    val hideGround: Boolean = false,
    val showRadar: Boolean = false,
    val radarTiles: String? = null,
    val showMetars: Boolean = false,
    val metars: List<Metar> = emptyList(),
    val selectedMetar: Metar? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val lastUpdate: Long = 0,
    /** Bumped when the map should move the camera to [cameraTarget]. */
    val cameraTarget: Aircraft? = null,
    val cameraNonce: Int = 0,
)

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    val provider = settings.provider.stateIn(viewModelScope, SharingStarted.Eagerly, Provider.ADSB_LOL)
    val watchlist = settings.watchlist.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val viewport = MutableStateFlow<Viewport?>(null)
    private val trails = HashMap<String, ArrayDeque<LatLon>>()
    private var enrichJob: Job? = null

    init {
        viewModelScope.launch {
            // Mode / filter changes call restartPolling() directly.
            combine(viewport.filterNotNull().debounce(400), provider) { v, p -> v to p }
                .collect { restartPolling() }
        }
    }

    private var pollJob: Job? = null

    private fun restartPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                poll()
                refreshMetarsIfNeeded()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun poll() {
        val v = viewport.value ?: return
        val p = provider.value
        val s = _state.value
        _state.update { it.copy(loading = true) }
        try {
            var list = when (s.mode) {
                Mode.ALL -> FlightData.area(p, v.lat, v.lon, v.radiusNm)
                Mode.MILITARY -> FlightData.military(p)
            }.filter { it.hasPosition }

            // Keep tracking the selected aircraft even after it leaves the queried area.
            val sel = _state.value.selected
            if (sel != null && list.none { it.hex == sel.hex }) {
                runCatching { FlightData.lookup(p, WatchKind.HEX, sel.hex) }
                    .getOrNull()?.firstOrNull { it.hasPosition }?.let { list = list + it }
            }

            recordTrails(list)
            val hide = _state.value.hideGround
            val shown = if (hide) list.filter { !it.onGround } else list
            val newSel = sel?.let { old -> list.firstOrNull { it.hex == old.hex } ?: old }
            _state.update {
                it.copy(
                    aircraft = shown,
                    selected = newSel,
                    selectedTrail = newSel?.let { a -> trails[a.hex]?.toList() }.orEmpty(),
                    loading = false,
                    error = null,
                    lastUpdate = System.currentTimeMillis(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Network error") }
        }
    }

    private fun recordTrails(list: List<Aircraft>) {
        val seen = HashSet<String>(list.size)
        for (a in list) {
            seen += a.hex
            val t = trails.getOrPut(a.hex) { ArrayDeque() }
            val pt = LatLon(a.lat!!, a.lon!!, a.altitude)
            if (t.lastOrNull()?.let { it.lat == pt.lat && it.lon == pt.lon } != true) t.addLast(pt)
            while (t.size > MAX_TRAIL) t.removeFirst()
        }
        // Drop trails for aircraft we no longer receive, except the selected one.
        val keep = _state.value.selected?.hex
        trails.keys.retainAll { it in seen || it == keep }
    }

    fun onViewportChanged(v: Viewport) {
        viewport.value = v
    }

    fun setMode(m: Mode) {
        if (m == _state.value.mode) return
        _state.update { it.copy(mode = m, aircraft = emptyList()) }
        trails.clear()
        restartPolling()
    }

    fun setHideGround(h: Boolean) {
        _state.update { it.copy(hideGround = h) }
        restartPolling()
    }

    fun setProvider(p: Provider) = viewModelScope.launch { settings.setProvider(p) }

    fun select(hex: String?) {
        enrichJob?.cancel()
        if (hex == null) {
            _state.update { it.copy(selected = null, enrichment = null, selectedTrail = emptyList()) }
            return
        }
        val ac = _state.value.aircraft.firstOrNull { it.hex == hex } ?: return
        selectAircraft(ac, moveCamera = false)
    }

    private fun selectAircraft(ac: Aircraft, moveCamera: Boolean) {
        enrichJob?.cancel()
        _state.update {
            it.copy(
                selected = ac,
                enrichment = null,
                selectedMetar = null,
                selectedTrail = trails[ac.hex]?.toList().orEmpty(),
                cameraTarget = if (moveCamera) ac else it.cameraTarget,
                cameraNonce = if (moveCamera) it.cameraNonce + 1 else it.cameraNonce,
            )
        }
        enrichJob = viewModelScope.launch {
            val e = FlightData.enrich(ac)
            _state.update { if (it.selected?.hex == ac.hex) it.copy(enrichment = e) else it }
        }
    }

    /** Find by registration / callsign / hex anywhere in the world and fly to it. */
    fun search(query: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val hit = runCatching { FlightData.search(provider.value, query) }
            .getOrDefault(emptyList()).firstOrNull { it.hasPosition }
        if (hit != null) {
            _state.update { s -> s.copy(aircraft = s.aircraft.filter { it.hex != hit.hex } + hit) }
            selectAircraft(hit, moveCamera = true)
        }
        onResult(hit != null)
    }

    /** Opened from an alert notification. */
    fun focusHex(hex: String) = search(hex) {}

    fun toggleRadar() {
        val on = !_state.value.showRadar
        _state.update { it.copy(showRadar = on) }
        if (on) viewModelScope.launch {
            val url = runCatching { FlightData.radarTileUrl() }.getOrNull()
            _state.update { it.copy(radarTiles = url, error = if (url == null) "Radar unavailable" else it.error) }
        }
    }

    fun toggleMetars() {
        val on = !_state.value.showMetars
        _state.update { it.copy(showMetars = on, metars = if (on) it.metars else emptyList(), selectedMetar = null) }
        if (on) viewModelScope.launch { lastMetarFetch = 0; refreshMetarsIfNeeded() }
    }

    fun selectMetar(id: String?) {
        _state.update { s -> s.copy(selectedMetar = s.metars.firstOrNull { it.icaoId == id }) }
        if (id != null) select(null)
    }

    private var lastMetarFetch = 0L
    private var lastMetarViewport: Viewport? = null

    private suspend fun refreshMetarsIfNeeded() {
        if (!_state.value.showMetars) return
        val v = viewport.value ?: return
        val stale = System.currentTimeMillis() - lastMetarFetch > METAR_MS
        if (!stale && v == lastMetarViewport) return
        // aviationweather.gov rejects huge boxes; only load when zoomed in reasonably.
        if (v.north - v.south > 20 || v.east - v.west > 30) {
            _state.update { it.copy(metars = emptyList()) }
            return
        }
        val m = runCatching { FlightData.metars(v.south, v.west, v.north, v.east) }.getOrNull() ?: return
        lastMetarFetch = System.currentTimeMillis()
        lastMetarViewport = v
        _state.update { it.copy(metars = m) }
    }

    fun addWatch(e: WatchEntry) = viewModelScope.launch {
        settings.addWatch(e)
        AlertWorker.schedule(getApplication())
    }

    fun removeWatch(e: WatchEntry) = viewModelScope.launch { settings.removeWatch(e) }

    fun clearError() = _state.update { it.copy(error = null) }

    companion object {
        private const val POLL_MS = 5_000L
        private const val METAR_MS = 10 * 60_000L
        private const val MAX_TRAIL = 720 // ~1 hour at 5 s
    }
}
