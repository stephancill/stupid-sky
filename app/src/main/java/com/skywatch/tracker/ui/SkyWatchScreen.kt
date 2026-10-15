package com.skywatch.tracker.ui

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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skywatch.tracker.data.Provider
import com.skywatch.tracker.data.WatchEntry
import com.skywatch.tracker.data.WatchKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyWatchScreen(vm: MainViewModel, requestNotifications: () -> Unit, requestLocation: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val watchlist by vm.watchlist.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var watchPrefill by remember { mutableStateOf<WatchEntry?>(null) }
    var showWatchDialog by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        MapHost(
            state = state,
            onViewport = vm::onViewportChanged,
            onAircraftTap = vm::select,
            onMetarTap = vm::selectMetar,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBar(onMenu = { showSettings = true }, onSearch = vm::search)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow {
                    Mode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = state.mode == m,
                            onClick = { vm.setMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                        ) { Text(if (m == Mode.ALL) "All traffic" else "Military (global)") }
                    }
                }
                Spacer(Modifier.weight(1f))
                FilledIconToggleButton(checked = state.showRadar, onCheckedChange = { vm.toggleRadar() }) {
                    Icon(Icons.Default.Cloud, "Weather radar")
                }
                FilledIconToggleButton(checked = state.showMetars, onCheckedChange = { vm.toggleMetars() }) {
                    Icon(Icons.Default.Thermostat, "METARs")
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                StatusPill(state, provider, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
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
                metar != null -> MetarPanel(metar, onClose = { vm.selectMetar(null) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsContent(
                provider = provider,
                onProvider = vm::setProvider,
                hideGround = state.hideGround,
                onHideGround = vm::setHideGround,
                watchlist = watchlist,
                onAddWatch = { watchPrefill = null; showWatchDialog = true },
                onRemoveWatch = vm::removeWatch,
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
private fun SearchBar(onMenu: () -> Unit, onSearch: (String, (Boolean) -> Unit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var notFound by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, tonalElevation = 2.dp) {
        TextField(
            value = query,
            onValueChange = { query = it; notFound = false },
            placeholder = { Text(if (notFound) "Not found / not airborne" else "Callsign, registration or hex") },
            leadingIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Settings") } },
            trailingIcon = { Icon(Icons.Default.Search, null) },
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
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatusPill(state: UiState, provider: Provider, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color(0xCC000000)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(6.dp))
            }
            val mil = state.aircraft.count { it.isMilitary }
            val text = state.error?.let { "⚠ $it" }
                ?: "${state.aircraft.size} aircraft · $mil mil · ${provider.label}"
            Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SettingsContent(
    provider: Provider,
    onProvider: (Provider) -> Unit,
    hideGround: Boolean,
    onHideGround: (Boolean) -> Unit,
    watchlist: List<WatchEntry>,
    onAddWatch: () -> Unit,
    onRemoveWatch: (WatchEntry) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text("Data source", style = MaterialTheme.typography.titleMedium)
        Provider.entries.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = p == provider, onClick = { onProvider(p) })
                Text(p.label)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hide aircraft on the ground", Modifier.weight(1f))
            Switch(checked = hideGround, onCheckedChange = onHideGround)
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
        Text(
            "Positions: adsb.lol (ODbL) / adsb.fi open data · Routes & aircraft: adsbdb.com · " +
                "Photos: Planespotters.net · Weather: NOAA aviationweather.gov, RainViewer · " +
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
