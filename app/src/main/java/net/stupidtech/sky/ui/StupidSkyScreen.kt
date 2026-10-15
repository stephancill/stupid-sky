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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    var detailCollapsed by rememberSaveable { mutableStateOf(false) }
    // Each newly selected aircraft opens expanded.
    LaunchedEffect(state.selected?.hex) { detailCollapsed = false }

    Box(Modifier.fillMaxSize()) {
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
                FilledTonalIconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                Spacer(Modifier.weight(1f))
                FilledIconToggleButton(checked = showSearch, onCheckedChange = { showSearch = it }) {
                    Icon(Icons.Default.Search, "Search")
                }
                FilledIconToggleButton(checked = state.showRadar, onCheckedChange = { vm.toggleRadar() }) {
                    Icon(Icons.Default.Cloud, "Weather radar")
                }
                FilledIconToggleButton(checked = state.showMetars, onCheckedChange = { vm.toggleMetars() }) {
                    Icon(Icons.Default.Thermostat, "METARs")
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

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                StatusPill(state, onClick = { showList = true }, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                SmallFloatingActionButton(
                    onClick = vm::refreshArea,
                    containerColor = if (state.areaStale) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (state.areaStale) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                ) { Icon(Icons.Default.Refresh, if (state.areaStale) "Refresh this area" else "Refresh now") }
                Spacer(Modifier.width(8.dp))
                SmallFloatingActionButton(onClick = {
                    if (state.locationGranted) vm.locateUser() else requestLocation()
                }) { Icon(Icons.Default.MyLocation, "My location") }
            }
            val sel = state.selected
            val metar = state.selectedMetar
            when {
                sel != null -> AircraftPanel(
                    ac = sel,
                    enrichment = state.enrichment,
                    routeInfo = sel.callsign?.let { state.routes[it] },
                    collapsed = detailCollapsed,
                    onCollapsedChange = { c ->
                        detailCollapsed = c
                        if (c) vm.fitSelectedPath()
                    },
                    onClose = { vm.select(null) },
                    onWatch = {
                        watchPrefill = when {
                            sel.registration != null -> WatchEntry(WatchKind.REG, sel.registration)
                            else -> WatchEntry(WatchKind.HEX, sel.hex)
                        }
                        showWatchDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                )
                state.selectedAirport != null -> AirportPanel(
                    airport = state.selectedAirport!!,
                    metar = state.airportMetar,
                    aircraft = state.aircraft,
                    routes = state.routes,
                    onPick = vm::selectAndFocus,
                    onClose = { vm.selectAirport(null) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                )
                metar != null -> MetarPanel(metar, onClose = { vm.selectMetar(null) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
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
        ModalBottomSheet(onDismissRequest = { showList = false }) {
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
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp), color = Color(0xCC000000)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Aircraft list", tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Flight, contentDescription = "Aircraft in view", tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("$inView", color = Color.White, style = MaterialTheme.typography.labelLarge)
            if (state.loading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
            }
            if (state.error != null) {
                Spacer(Modifier.width(8.dp))
                Text("⚠ ${shortError(state.error)}", color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
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
                "Map: OpenFreeMap, © OpenStreetMap contributors",
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
