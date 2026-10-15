package com.skywatch.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skywatch.tracker.alerts.AlertWorker
import com.skywatch.tracker.data.Aircraft
import com.skywatch.tracker.data.BoundingBox
import com.skywatch.tracker.data.OpenSky
import com.skywatch.tracker.data.Enrichment
import com.skywatch.tracker.data.FlightData
import com.skywatch.tracker.data.LatLon
import com.skywatch.tracker.data.Logos
import com.skywatch.tracker.data.Trace
import com.skywatch.tracker.data.Metar
import com.skywatch.tracker.data.RateLimitedException
import com.skywatch.tracker.data.RouteInfo
import com.skywatch.tracker.data.Routes
import com.skywatch.tracker.data.Settings
import com.skywatch.tracker.data.UserLocation
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Viewport(
    val lat: Double, val lon: Double, val radiusNm: Int,
    val south: Double, val west: Double, val north: Double, val east: Double,
)

/** A one-off request for the map to move. [zoom] null keeps the current zoom (but at least 8). */
data class CameraMove(
    val lat: Double,
    val lon: Double,
    val zoom: Double?,
    val id: Int,
    /** When set, fit these bounds instead of centring on lat/lon. */
    val fit: BoundingBox? = null,
)

data class UiState(
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
    val camera: CameraMove? = null,
    val locationGranted: Boolean = false,
    /** Aircraft added by OpenSky that the main feed didn't have. */
    val openSkyAdded: Int = 0,
    val openSkyStatus: String? = null,
    /** What the map currently shows (for the list view). */
    val viewport: Viewport? = null,
    /** The map has moved away from the area last loaded; offer "Refresh this area". */
    val areaStale: Boolean = false,
    /** Networks that failed on the last refresh (the others still supplied data). */
    val failedSources: List<String> = emptyList(),
    /** Airline and origin/destination by callsign, filled in progressively in the background. */
    val routes: Map<String, RouteInfo> = emptyMap(),
    /** IATA codes whose logo card is loaded (bitmaps live in [Logos]). */
    val logos: Set<String> = emptySet(),
    val showLogos: Boolean = true,
)

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    private val _state = MutableStateFlow(UiState(locationGranted = UserLocation.hasPermission(app)))
    val state: StateFlow<UiState> = _state

    val watchlist = settings.watchlist.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val logosEnabled = settings.logosEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val openSkyEnabled = settings.openSkyEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val openSkyCredentials = settings.openSkyCredentials.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val viewport = MutableStateFlow<Viewport?>(null)
    /** The area being refreshed. Only changes on "Refresh this area" or app-driven camera moves. */
    private var loadedArea: Viewport? = null
    private var autoLoadNext = true
    private val trails = HashMap<String, ArrayDeque<LatLon>>()
    private var enrichJob: Job? = null
    private var traceJob: Job? = null
    /** Flown path of the selected aircraft's current flight, from the trace history. */
    private var selectedHistory: List<LatLon> = emptyList()
    private var pollJob: Job? = null
    private var active = false
    private var lastPollAt = 0L
    private var pollCount = 0
    private var backoffMs = 0L
    private var cameraId = 0

    // Latest results from each source, merged into state.aircraft.
    private var lastMain: List<Aircraft> = emptyList()
    private var openSkyList: List<Aircraft> = emptyList()
    private var openSkyFetchedAt = 0L
    private var openSkyBox: BoundingBox? = null
    private var openSkyCredits: Int? = null
    private var openSkyRetryAt = 0L
    private var openSkyJob: Job? = null

    init {
        viewModelScope.launch {
            logosEnabled.collect { on ->
                _state.update { it.copy(showLogos = on) }
                if (on) loadLogos()
            }
        }
        viewModelScope.launch {
            combine(openSkyEnabled, openSkyCredentials) { on, c -> on to c }.collect {
                openSkyCredits = null
                openSkyRetryAt = 0
                restartOpenSky(immediate = true)
            }
        }
    }

    /** Called from the activity: poll only while the app is on screen. */
    fun setActive(isActive: Boolean) {
        active = isActive
        if (isActive) {
            restartPolling()
            restartOpenSky(immediate = false)
        } else {
            pollJob?.cancel()
            openSkyJob?.cancel()
        }
    }

    private fun restartOpenSky(immediate: Boolean) {
        openSkyJob?.cancel()
        if (!active || !openSkyEnabled.value) {
            if (!openSkyEnabled.value) {
                openSkyList = emptyList()
                publish()
            }
            return
        }
        openSkyJob = viewModelScope.launch {
            val hasAccount = openSkyCredentials.value != null
            if (!immediate) {
                val due = openSkyFetchedAt + OpenSky.pollIntervalSec(openSkyCredits, hasAccount) * 1000
                val wait = due - System.currentTimeMillis()
                if (wait > 0) delay(wait)
            }
            while (true) {
                val retryWait = openSkyRetryAt - System.currentTimeMillis()
                if (retryWait > 0) delay(retryWait)
                pollOpenSky()
                delay(OpenSky.pollIntervalSec(openSkyCredits, hasAccount) * 1000)
            }
        }
    }

    private suspend fun pollOpenSky() {
        val v = loadedArea ?: return
        val box = BoundingBox(v.south, v.west, v.north, v.east)
        try {
            val r = OpenSky.states(box, openSkyCredentials.value)
            openSkyList = r.aircraft
            openSkyFetchedAt = System.currentTimeMillis()
            openSkyBox = box.cropAroundCentre(5.0)
            openSkyCredits = r.creditsRemaining ?: openSkyCredits
            openSkyStatus(null)
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenSky.RateLimited) {
            val wait = e.retryAfterSec ?: OpenSky.secondsUntilReset()
            openSkyRetryAt = System.currentTimeMillis() + wait * 1000
            openSkyCredits = 0
            openSkyStatus("OpenSky credits used up · back in ${formatDuration(wait)}")
        } catch (e: Exception) {
            openSkyStatus("OpenSky: ${e.message ?: "error"}")
        }
    }

    private fun openSkyStatus(error: String?) {
        val text = error ?: run {
            val next = OpenSky.pollIntervalSec(openSkyCredits, openSkyCredentials.value != null)
            val credits = openSkyCredits?.let { "$it credits left · " }.orEmpty()
            "OpenSky: ${credits}refresh every ${formatDuration(next)}"
        }
        _state.update { it.copy(openSkyStatus = text) }
    }

    /** Merge main feed + OpenSky gap-fill and push to the UI. */
    private fun publish() {
        val now = System.currentTimeMillis()
        val mainHex = lastMain.mapTo(HashSet()) { it.hex }
        val sinceFetch = (now - openSkyFetchedAt) / 1000.0
        val extra = run {
            openSkyList.filter { a ->
                if (a.hex in mainHex) return@filter false
                val age = (a.seenPos ?: a.seen ?: 0.0) + sinceFetch
                // Parked aircraft barely move, so older ground positions are still useful;
                // airborne ones go stale quickly.
                if (a.onGround) age < 15 * 60 else age < 60
            }
        }

        val merged = lastMain + extra
        recordTrails(merged)
        val filtered = if (_state.value.hideGround) merged.filter { !it.onGround } else merged
        _state.update { s ->
            val newSel = s.selected?.let { old -> merged.firstOrNull { it.hex == old.hex } ?: old }
            // Keep a selected aircraft on the map even if neither feed returned it this time.
            val shown = if (newSel != null && filtered.none { it.hex == newSel.hex }) filtered + newSel else filtered
            s.copy(
                aircraft = shown,
                selected = newSel,
                selectedTrail = newSel?.let { a -> fullPath(a.hex) }.orEmpty(),
                openSkyAdded = extra.size,
            )
        }
        resolveRoutes()
    }

    private var routeJob: Job? = null

    /**
     * Fetch routes for callsigns we haven't looked up yet, nearest to the map centre first.
     * Runs one request at a time (Routes goes through the per-host throttle) and pushes results
     * to the UI in small batches.
     */
    private fun resolveRoutes() {
        if (routeJob?.isActive == true) return
        routeJob = viewModelScope.launch {
            while (true) {
                val s = _state.value
                val centre = s.viewport ?: loadedArea
                val pending = s.aircraft
                    .filter { it.callsign != null && it.callsign !in s.routes }
                    .sortedBy { a ->
                        // Selected aircraft first, then by distance from the centre.
                        if (a.hex == s.selected?.hex) -1.0
                        else centre?.let { c -> (a.lat!! - c.lat).let { it * it } + (a.lon!! - c.lon).let { it * it } } ?: 0.0
                    }
                    .mapNotNull { it.callsign }
                    .distinct()
                    .take(5)
                if (pending.isEmpty()) break
                val found = pending.mapNotNull { cs -> Routes.resolve(cs)?.let { cs to it } }.toMap()
                // Nothing resolved (offline / rate limited): stop and try again after the next refresh.
                if (found.isEmpty()) break
                _state.update { it.copy(routes = it.routes + found) }
                loadLogos()
            }
        }
    }

    private var logoJob: Job? = null

    /** Download logos for airlines in view, nearest aircraft first, one card per airline. */
    private fun loadLogos() {
        if (!logosEnabled.value || logoJob?.isActive == true) return
        logoJob = viewModelScope.launch {
            while (true) {
                val s = _state.value
                val centre = s.viewport ?: loadedArea
                val next = s.aircraft
                    .sortedBy { a -> centre?.let { c -> (a.lat!! - c.lat).let { it * it } + (a.lon!! - c.lon).let { it * it } } ?: 0.0 }
                    .mapNotNull { a -> a.callsign?.let { s.routes[it]?.airlineIata } }
                    .distinct()
                    .firstOrNull { !Logos.isKnown(it) }
                    ?: break
                if (Logos.load(next)) {
                    _state.update { it.copy(logos = it.logos + next) }
                } else if (!Logos.isKnown(next)) {
                    break // network trouble: retry after the next refresh
                }
            }
        }
    }

    fun setLogosEnabled(on: Boolean) = viewModelScope.launch { settings.setLogosEnabled(on) }

    private fun restartPolling(immediate: Boolean = false) {
        pollJob?.cancel()
        if (!active) return
        pollJob = viewModelScope.launch {
            // Keep a small gap between requests, and respect any rate-limit backoff.
            val sinceLast = System.currentTimeMillis() - lastPollAt
            val gap = if (immediate) MIN_GAP_MS else POLL_MS
            val wait = maxOf(gap - sinceLast, backoffMs - sinceLast)
            if (wait > 0) delay(wait)
            while (true) {
                poll()
                refreshMetarsIfNeeded()
                delay(if (backoffMs > 0) backoffMs else POLL_MS)
            }
        }
    }

    private suspend fun poll() {
        val v = loadedArea ?: return
        lastPollAt = System.currentTimeMillis()
        pollCount++
        _state.update { it.copy(loading = true) }
        try {
            val result = FlightData.area(v.lat, v.lon, v.radiusNm)
            var list = result.aircraft.filter { it.hasPosition }

            // Keep tracking the selected aircraft after it leaves the queried area,
            // but only every third poll to keep request volume down.
            val sel = _state.value.selected
            if (sel != null && list.none { it.hex == sel.hex } && pollCount % 3 == 0) {
                runCatching { FlightData.lookup(WatchKind.HEX, sel.hex) }
                    .getOrNull()?.firstOrNull { it.hasPosition }?.let { list = list + it }
            }

            backoffMs = 0
            lastMain = list
            publish()
            _state.update {
                it.copy(
                    loading = false,
                    error = null,
                    lastUpdate = System.currentTimeMillis(),
                    failedSources = result.failed.map { f -> f.label },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitedException) {
            // Honour Retry-After when sent, otherwise back off exponentially: 20 s, 40 s, 80 s, 160 s max.
            backoffMs = e.retryAfterSec?.times(1000)
                ?: (if (backoffMs == 0L) 20_000L else (backoffMs * 2).coerceAtMost(160_000L))
            _state.update {
                it.copy(loading = false, error = "${e.host} is busy, retrying in ${backoffMs / 1000}s")
            }
        } catch (e: Exception) {
            backoffMs = 0
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
        val loaded = loadedArea
        if (loaded == null || autoLoadNext) {
            loadArea(v)
        } else {
            _state.update { it.copy(viewport = v, areaStale = movedAway(loaded, v)) }
        }
    }

    /** "Refresh this area" button, or the refresh icon. */
    fun refreshArea() {
        viewport.value?.let { loadArea(it, manual = true) }
    }

    private fun loadArea(v: Viewport, manual: Boolean = false) {
        val previous = loadedArea
        loadedArea = v
        autoLoadNext = false
        if (previous != null && movedAway(previous, v)) {
            trails.clear()
            lastMain = emptyList()
        }
        _state.update { it.copy(viewport = v, areaStale = false) }
        restartPolling(immediate = true)
        // OpenSky credits are scarce: re-query only if the new area isn't covered by the last
        // query and we haven't just fetched.
        val box = openSkyBox
        val covered = box != null && v.lat in box.south..box.north && v.lon in box.west..box.east
        val minGap = if (openSkyCredentials.value != null) 30_000L else 120_000L
        if ((!covered || manual) && System.currentTimeMillis() - openSkyFetchedAt > minGap) {
            restartOpenSky(immediate = true)
        }
    }

    /** True when the view no longer matches the loaded area well enough. */
    private fun movedAway(loaded: Viewport, v: Viewport): Boolean {
        val dLat = (v.lat - loaded.lat) * 60            // nm
        val dLon = (v.lon - loaded.lon) * 60 * kotlin.math.cos(Math.toRadians(v.lat))
        val shift = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
        val zoomRatio = v.radiusNm.toDouble() / loaded.radiusNm
        return shift > loaded.radiusNm * 0.3 || zoomRatio > 1.6 || zoomRatio < 0.5
    }

    // ---- location ----

    fun onLocationPermission(granted: Boolean) {
        _state.update { it.copy(locationGranted = granted) }
        if (granted) locateUser()
    }

    /** Centre the map on the device; used at startup and by the "my location" button. */
    fun locateUser() = viewModelScope.launch {
        val loc = UserLocation.current(getApplication()) ?: run {
            _state.update { it.copy(error = "Location unavailable") }
            return@launch
        }
        moveCamera(loc.latitude, loc.longitude, USER_ZOOM)
    }

    private fun moveCamera(lat: Double, lon: Double, zoom: Double?) {
        // App-driven moves (my location, search) load the new area automatically.
        autoLoadNext = true
        _state.update { it.copy(camera = CameraMove(lat, lon, zoom, ++cameraId)) }
    }

    // ---- controls ----

    fun setHideGround(h: Boolean) {
        _state.update { it.copy(hideGround = h) }
        publish()
    }

    fun setOpenSkyEnabled(on: Boolean) = viewModelScope.launch {
        settings.setOpenSkyEnabled(on)
        if (!on) _state.update { it.copy(openSkyStatus = null, openSkyAdded = 0) }
    }

    fun setOpenSkyCredentials(id: String, secret: String) = viewModelScope.launch {
        settings.setOpenSkyCredentials(id, secret)
    }

    /** History from the trace API followed by positions we've received live since. */
    private fun fullPath(hex: String): List<LatLon> = selectedHistory + trails[hex]?.toList().orEmpty()

    fun select(hex: String?) {
        enrichJob?.cancel()
        if (hex == null) {
            traceJob?.cancel()
            selectedHistory = emptyList()
            _state.update { it.copy(selected = null, enrichment = null, selectedTrail = emptyList()) }
            return
        }
        val ac = _state.value.aircraft.firstOrNull { it.hex == hex } ?: return
        selectAircraft(ac, moveCamera = false)
    }

    /**
     * Zoom the map to show the selected aircraft's flown path and its destination.
     * Doesn't reload the area (the selected aircraft keeps updating regardless).
     */
    fun fitSelectedPath() {
        val s = _state.value
        val sel = s.selected ?: return
        val dest = sel.callsign?.let { s.routes[it]?.destination } ?: s.enrichment?.route?.destination
        val pts = s.selectedTrail.map { it.lat to it.lon } +
            listOfNotNull(sel.lat?.let { la -> sel.lon?.let { lo -> la to lo } }) +
            listOfNotNull(dest?.latitude?.let { la -> dest.longitude?.let { lo -> la to lo } })
        if (pts.isEmpty()) return
        // Pad a little so a single point (or a parked aircraft) still gives a valid, sensible view.
        val m = 0.02
        val box = BoundingBox(
            south = pts.minOf { it.first } - m, west = pts.minOf { it.second } - m,
            north = pts.maxOf { it.first } + m, east = pts.maxOf { it.second } + m,
        )
        _state.update { it.copy(camera = CameraMove(sel.lat!!, sel.lon!!, null, ++cameraId, fit = box)) }
    }

    /** From the list view: select and centre on the aircraft without reloading the area. */
    fun selectAndFocus(hex: String) {
        val ac = _state.value.aircraft.firstOrNull { it.hex == hex } ?: return
        selectAircraft(ac, moveCamera = false)
        _state.update { it.copy(camera = CameraMove(ac.lat!!, ac.lon!!, null, ++cameraId)) }
    }

    private fun selectAircraft(ac: Aircraft, moveCamera: Boolean) {
        enrichJob?.cancel()
        if (_state.value.selected?.hex != ac.hex) {
            traceJob?.cancel()
            selectedHistory = emptyList()
            traceJob = viewModelScope.launch {
                val path = Trace.currentFlight(ac.hex, openSkyCredentials.value)
                if (_state.value.selected?.hex != ac.hex) return@launch
                selectedHistory = path
                // Live points start fresh from here so they don't duplicate the history.
                trails[ac.hex]?.clear()
                _state.update { it.copy(selectedTrail = fullPath(ac.hex)) }
            }
        }
        _state.update {
            it.copy(
                selected = ac,
                enrichment = null,
                selectedMetar = null,
                selectedTrail = fullPath(ac.hex),
            )
        }
        if (moveCamera) moveCamera(ac.lat!!, ac.lon!!, null)
        enrichJob = viewModelScope.launch {
            val e = FlightData.enrich(ac)
            _state.update { if (it.selected?.hex == ac.hex) it.copy(enrichment = e) else it }
        }
    }

    /** Find by registration / callsign / hex anywhere in the world and fly to it. */
    fun search(query: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val hit = runCatching { FlightData.search(query) }
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
        val v = loadedArea ?: return
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

    private fun formatDuration(sec: Long): String = when {
        sec < 90 -> "${sec}s"
        sec < 5400 -> "${(sec + 30) / 60} min"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
    }

    companion object {
        private const val POLL_MS = 10_000L
        private const val MIN_GAP_MS = 1_500L
        private const val METAR_MS = 10 * 60_000L
        private const val MAX_TRAIL = 360 // ~1 hour at 10 s
        private const val USER_ZOOM = 8.5
    }
}
