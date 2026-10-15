package com.skywatch.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skywatch.tracker.data.Aircraft
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private enum class SortBy(val label: String) { DISTANCE("Nearest"), ALTITUDE("Altitude"), CALLSIGN("Callsign"), SPEED("Speed") }
private enum class Show(val label: String) { ALL("All"), AIRBORNE("Airborne"), GROUND("Ground"), MILITARY("Military") }

/** Aircraft currently inside the visible map, sortable and filterable. */
@Composable
fun AircraftList(aircraft: List<Aircraft>, viewport: Viewport?, onPick: (String) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(SortBy.DISTANCE) }
    var show by rememberSaveable { mutableStateOf(Show.ALL) }
    var query by rememberSaveable { mutableStateOf("") }

    val rows = remember(aircraft, viewport, sort, show, query) {
        val q = query.trim()
        aircraft.asSequence()
            .filter { a -> viewport == null || a.inside(viewport) }
            .filter { a ->
                when (show) {
                    Show.ALL -> true
                    Show.AIRBORNE -> !a.onGround
                    Show.GROUND -> a.onGround
                    Show.MILITARY -> a.isMilitary
                }
            }
            .filter { a ->
                q.isEmpty() || listOfNotNull(a.callsign, a.registration, a.typeCode, a.desc, a.hex)
                    .any { it.contains(q, ignoreCase = true) }
            }
            .map { a -> a to (viewport?.let { distanceNm(it.lat, it.lon, a.lat!!, a.lon!!) } ?: 0.0) }
            .let { seq ->
                when (sort) {
                    SortBy.DISTANCE -> seq.sortedBy { it.second }
                    SortBy.ALTITUDE -> seq.sortedByDescending { if (it.first.onGround) -1 else it.first.altitude ?: 0 }
                    SortBy.CALLSIGN -> seq.sortedBy { it.first.label }
                    SortBy.SPEED -> seq.sortedByDescending { it.first.gs ?: 0.0 }
                }
            }
            .toList()
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "${rows.size} aircraft in view",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Filter by callsign, reg, type") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        ChipRow(Show.entries, show, { it.label }) { show = it }
        ChipRow(SortBy.entries, sort, { it.label }) { sort = it }
        HorizontalDivider()
        if (rows.isEmpty()) {
            Text(
                "No aircraft match.",
                Modifier.fillMaxWidth().padding(24.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(rows, key = { it.first.hex }) { (a, dist) ->
                AircraftRow(a, dist) { onPick(a.hex) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
private fun AircraftRow(a: Aircraft, distNm: Double, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                if (a.isEmergency) Tag("SQK ${a.squawk ?: ""}", Color(0xFFD50000))
                if (a.isMilitary) Tag("MIL", Color(0xFF33691E))
                if (a.fromOpenSky) Tag("OS", Color(0xFF0277BD))
            }
            Text(
                listOfNotNull(a.typeCode ?: a.desc, a.registration?.takeIf { it != a.label }).joinToString(" · ")
                    .ifEmpty { a.hex.uppercase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val vr = a.verticalRate ?: 0
            val arrow = when { a.onGround -> ""; vr > 300 -> " ▲"; vr < -300 -> " ▼"; else -> "" }
            Text(
                if (a.onGround) "Ground" else a.altitude?.let { "%,d ft".format(it) + arrow } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOfNotNull(a.gs?.let { "${it.toInt()} kt" }, "%.0f nm".format(distNm)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(end = 4.dp)
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private fun Aircraft.inside(v: Viewport): Boolean {
    val la = lat ?: return false
    val lo = lon ?: return false
    if (la !in v.south..v.north) return false
    return if (v.west <= v.east) lo in v.west..v.east else lo >= v.west || lo <= v.east // antimeridian
}

private fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * 3440.065 * asin(sqrt(a))
}
