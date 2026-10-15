package net.stupidtech.sky.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.stupidtech.sky.data.WatchEntry
import net.stupidtech.sky.data.WatchKind
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StupidSkyScreen(vm: MainViewModel, requestNotifications: () -> Unit, requestLocation: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val watchlist by vm.watchlist.collectAsStateWithLifecycle()
    val openSkyOn by vm.openSkyEnabled.collectAsStateWithLifecycle()
    val openSkyCreds by vm.openSkyCredentials.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var watchPrefill by remember { mutableStateOf<WatchEntry?>(null) }
    var showWatchDialog by rememberSaveable { mutableStateOf(false) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val sheetState = remember { AnchoredDraggableState(SheetStop.Hidden) }
    val scope = rememberCoroutineScope()
    // Minimising to Peek frames the flight path, as the old collapse button did.
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.settledValue }.collect { if (it == SheetStop.Peek) vm.fitSelectedPath() }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val containerH = constraints.maxHeight.toFloat()
        MapHost(
            state = state,
            onViewport = vm::onViewportChanged,
            onAircraftTap = vm::select,
            onMetarTap = vm::selectMetar,
            onAirportTap = vm::selectAirport,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MapButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                Spacer(Modifier.weight(1f))
                MapButton(onClick = { showSearch = !showSearch }, active = showSearch) {
                    Icon(Icons.Default.Search, "Search")
                }
            }
            AnimatedVisibility(visible = showSearch) {
                SearchBar(
                    onClose = { showSearch = false },
                    onSearch = { q, done -> vm.search(q) { found -> done(found); if (found) showSearch = false } },
                )
            }
            // Panning never triggers a request on its own; offer an explicit refresh instead.
            // Re-check every 15 s so the banner can appear without any other state change.
            val now by produceState(System.currentTimeMillis()) {
                while (true) { delay(15_000); value = System.currentTimeMillis() }
            }
            val staleLong = state.areaStaleSince?.let { now - it >= STALE_BANNER_MS } == true
            AnimatedVisibility(
                visible = state.areaStale && staleLong,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                ExtendedFloatingActionButton(
                    onClick = vm::refreshArea,
                    icon = { Icon(Icons.Default.Refresh, null) },
                    text = { Text("Refresh this area") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        val sel = state.selected
        val airport = state.selectedAirport
        val metar = state.selectedMetar
        val sheetKey: Any? = sel?.hex ?: airport?.let { "apt:${it.icao ?: it.name}" } ?: metar?.let { "metar:${it.icaoId}" }
        val density = LocalDensity.current
        val navBottom = WindowInsets.navigationBars.getBottom(density)
        val gap = with(density) { 8.dp.toPx() }
        // Top edge of the sheet, read only in layout/draw so dragging doesn't recompose the screen.
        fun sheetTop(): Float =
            if (sheetKey == null) containerH else sheetState.offset.takeUnless { it.isNaN() } ?: containerH
        // The map buttons ride on the sheet and fade out as it passes the middle of the screen.
        fun buttonsAlpha(): Float = ((sheetTop() - containerH * 0.3f) / (containerH * 0.15f)).coerceIn(0f, 1f)
        val buttonsVisible by remember(sheetKey) { derivedStateOf { buttonsAlpha() > 0.05f } }
        var rowH by remember { mutableIntStateOf(0) }
        if (buttonsVisible) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .onSizeChanged { rowH = it.height }
                    .offset {
                        val bottom = minOf(sheetTop(), containerH - navBottom)
                        IntOffset(0, (bottom - rowH - gap).roundToInt())
                    }
                    .graphicsLayer { alpha = buttonsAlpha() }
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                // Only the pill is weighted (fill = false) so the button group sits flush with the right edge.
                StatusPill(state, onClick = { showList = true }, modifier = Modifier.weight(1f, fill = false))
                Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapButton(onClick = vm::refreshArea, active = state.areaStale) {
                        Icon(Icons.Default.Refresh, if (state.areaStale) "Refresh this area" else "Refresh now")
                    }
                    MapButton(onClick = {
                        if (state.locationGranted) vm.locateUser() else requestLocation()
                    }) { Icon(Icons.Default.MyLocation, "My location") }
                }
            }
        }

        if (sheetKey != null) {
            val close: () -> Unit = { scope.launch { sheetState.animateTo(SheetStop.Hidden, SheetSpring) } }
            DetailSheet(
                state = sheetState,
                key = sheetKey,
                containerHeight = containerH,
                onDismiss = {
                    when {
                        sel != null -> vm.select(null)
                        airport != null -> vm.selectAirport(null)
                        else -> vm.selectMetar(null)
                    }
                },
            ) { headerModifier ->
                when {
                    sel != null -> AircraftPanel(
                        ac = sel,
                        enrichment = state.enrichment,
                        routeInfo = sel.callsign?.let { state.routes[it] },
                        legStart = state.legStart,
                        minimised = sheetState.targetValue == SheetStop.Peek,
                        onToggleMinimised = {
                            scope.launch {
                                val to = if (sheetState.targetValue == SheetStop.Peek) sheetState.expandedStop() else SheetStop.Peek
                                sheetState.animateTo(to, SheetSpring)
                            }
                        },
                        onClose = close,
                        onWatch = {
                            watchPrefill = when {
                                sel.registration != null -> WatchEntry(WatchKind.REG, sel.registration)
                                else -> WatchEntry(WatchKind.HEX, sel.hex)
                            }
                            showWatchDialog = true
                        },
                        headerModifier = headerModifier,
                    )
                    airport != null -> AirportPanel(
                        airport = airport,
                        metar = state.airportMetar,
                        aircraft = state.aircraft,
                        routes = state.routes,
                        onPick = vm::selectAndFocus,
                        onClose = close,
                        headerModifier = headerModifier,
                    )
                    metar != null -> MetarPanel(metar, onClose = close, headerModifier = headerModifier)
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = MaterialTheme.colorScheme.surface) {
            SettingsContent(
                showLogos = state.showLogos,
                onShowLogos = vm::setLogosEnabled,
                openSkyOn = openSkyOn,
                onOpenSky = vm::setOpenSkyEnabled,
                openSkyHasAccount = openSkyCreds != null,
                openSkyClientId = openSkyCreds?.clientId.orEmpty(),
                openSkyStatus = state.openSkyStatus,
                onOpenSkyCredentials = vm::setOpenSkyCredentials,
                watchlist = watchlist,
                onAddWatch = { watchPrefill = null; showWatchDialog = true },
                onRemoveWatch = vm::removeWatch,
            )
        }
    }

    if (showList) {
        ModalBottomSheet(onDismissRequest = { showList = false }, containerColor = MaterialTheme.colorScheme.surface) {
            AircraftList(
                aircraft = state.aircraft,
                routes = state.routes,
                viewport = state.viewport,
                onPick = { hex ->
                    showList = false
                    vm.selectAndFocus(hex)
                },
            )
        }
    }

    if (showWatchDialog) {
        WatchDialog(
            initial = watchPrefill,
            onDismiss = { showWatchDialog = false },
            onAdd = {
                vm.addWatch(it)
                requestNotifications()
                showWatchDialog = false
            },
        )
    }
}

@Composable
private fun SearchBar(onClose: () -> Unit, onSearch: (String, (Boolean) -> Unit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var notFound by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    // Open the keyboard straight away when the search bar appears.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, tonalElevation = 2.dp) {
        TextField(
            value = query,
            onValueChange = { query = it; notFound = false },
            placeholder = { Text(if (notFound) "Not found / not airborne" else "Callsign, registration or hex") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                IconButton(onClick = { focus.clearFocus(); onClose() }) { Icon(Icons.Default.Close, "Close search") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                onSearch(query) { found -> notFound = !found; if (!found) query = "" }
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
    }
}

/** Bottom-left: list button with the number of aircraft in view; a spinner while loading, ⚠ on errors. */
@Composable
private fun StatusPill(state: UiState, onClick: () -> Unit, modifier: Modifier) {
    val inView = state.viewport?.let { v -> state.aircraft.count { it.inside(v) } } ?: state.aircraft.size
    // Tap to open the aircraft list.
    Surface(
        onClick = onClick,
        modifier = modifier.height(MAP_BUTTON_SIZE),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Aircraft list", modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Default.Flight, contentDescription = "Aircraft in view", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("$inView", style = MaterialTheme.typography.labelLarge)
            if (state.loading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            }
            if (state.error != null) {
                Spacer(Modifier.width(8.dp))
                Text("⚠ ${shortError(state.error)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private val MAP_BUTTON_SIZE = 48.dp

/** Every control floating over the map shares this look: a circle on the surface colour, primary when active. */
@Composable
private fun MapButton(onClick: () -> Unit, active: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(MAP_BUTTON_SIZE),
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

private fun shortError(e: String): String = when {
    "busy" in e || "Rate" in e -> "busy, retrying"
    "Location" in e -> "no location"
    "Radar" in e -> "radar unavailable"
    else -> "offline"
}

private const val STALE_BANNER_MS = 5 * 60_000L

@Composable
private fun SettingsContent(
    showLogos: Boolean,
    onShowLogos: (Boolean) -> Unit,
    openSkyOn: Boolean,
    onOpenSky: (Boolean) -> Unit,
    openSkyHasAccount: Boolean,
    openSkyClientId: String,
    openSkyStatus: String?,
    onOpenSkyCredentials: (String, String) -> Unit,
    watchlist: List<WatchEntry>,
    onAddWatch: () -> Unit,
    onRemoveWatch: (WatchEntry) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show airline logos on the map", Modifier.weight(1f))
            Switch(checked = showLogos, onCheckedChange = onShowLogos)
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Watchlist alerts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAddWatch) { Text("Add") }
        }
        Text(
            "Checked every 15 minutes in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (watchlist.isEmpty()) {
            Text("Nothing watched yet.", Modifier.padding(vertical = 8.dp))
        } else {
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(watchlist, key = { it.encode() }) { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.display, Modifier.weight(1f))
                        IconButton(onClick = { onRemoveWatch(e) }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        var advanced by rememberSaveable { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (advanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
        }
        AnimatedVisibility(visible = advanced) {
            Column(Modifier.padding(top = 8.dp)) {
                OpenSkySection(
                    on = openSkyOn,
                    onToggle = onOpenSky,
                    hasAccount = openSkyHasAccount,
                    clientId = openSkyClientId,
                    status = openSkyStatus,
                    onSave = onOpenSkyCredentials,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            "Positions: adsb.lol (ODbL) / adsb.fi open data / The OpenSky Network (opensky-network.org) · Routes & aircraft: adsbdb.com · " +
                "Photos: Planespotters.net · Airline logos: pics.avs.io · Weather: NOAA aviationweather.gov, RainViewer · " +
                "Airports: OurAirports · Airline codes: OpenFlights (ODbL) · Map: OpenFreeMap, © OpenStreetMap contributors",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchDialog(initial: WatchEntry?, onDismiss: () -> Unit, onAdd: (WatchEntry) -> Unit) {
    var kind by remember { mutableStateOf(initial?.kind ?: WatchKind.REG) }
    var value by remember { mutableStateOf(initial?.value ?: "") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alert me when airborne") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = kind.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Match on") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        WatchKind.entries.forEach { k ->
                            DropdownMenuItem(text = { Text(k.label) }, onClick = { kind = k; expanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    label = { Text(kind.hint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onAdd(WatchEntry(kind, value)) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OpenSkySection(
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    hasAccount: Boolean,
    clientId: String,
    status: String?,
    onSave: (String, String) -> Unit,
) {
    var id by rememberSaveable(clientId) { mutableStateOf(clientId) }
    var secret by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("OpenSky gap-fill", style = MaterialTheme.typography.titleMedium)
            Text(
                "Adds aircraft the main feed misses, e.g. on the ground at airports. Free, non-commercial.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = onToggle)
    }
    if (!on) return
    status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
    Text(
        if (hasAccount) "Using your OpenSky account (refreshes about every 30 s)."
        else "No account: refreshes about every 5 min. A free account makes it about every 30 s.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    OutlinedTextField(
        value = id,
        onValueChange = { id = it },
        label = { Text("API client ID (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = secret,
        onValueChange = { secret = it },
        label = { Text(if (hasAccount) "Client secret (saved)" else "Client secret") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    Row {
        TextButton(onClick = { uri.openUri("https://opensky-network.org/my-opensky/account") }) {
            Text("Get a free account")
        }
        Spacer(Modifier.weight(1f))
        if (hasAccount) TextButton(onClick = { id = ""; secret = ""; onSave("", "") }) { Text("Remove") }
        TextButton(
            enabled = id.isNotBlank() && secret.isNotBlank(),
            onClick = { onSave(id, secret); secret = "" },
        ) { Text("Save") }
    }
}
