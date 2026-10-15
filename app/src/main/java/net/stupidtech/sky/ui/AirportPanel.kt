package net.stupidtech.sky.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.stupidtech.sky.data.Aircraft
import net.stupidtech.sky.data.AirportInfo
import net.stupidtech.sky.data.Metar
import net.stupidtech.sky.data.RouteInfo
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Airport details plus a live mini-board built from what the app already knows:
 * inbound/outbound flights (by route) and aircraft on the ground nearby.
 */
@Composable
fun AirportPanel(
    airport: AirportInfo,
    metar: Metar?,
    aircraft: List<Aircraft>,
    routes: Map<String, RouteInfo>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val board = remember(airport, aircraft, routes) {
        fun km(a: Aircraft) = distanceKm(airport.lat, airport.lon, a.lat!!, a.lon!!)
        val withRoute = aircraft.map { it to it.callsign?.let { cs -> routes[cs] } }
        Board(
            arriving = withRoute.filter { (a, r) -> !a.onGround && airport.matches(r?.destination) }
                .sortedBy { km(it.first) },
            departing = withRoute.filter { (a, r) -> !a.onGround && airport.matches(r?.origin) && km(a) < 300 }
                .sortedBy { km(it.first) },
            onGround = withRoute.filter { (a, _) -> a.onGround && km(a) < 4 },
        )
    }

    Card(modifier, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(airport.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(airport.iata, airport.icao, airport.city).distinct().joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            Row {
                if (airport.military) Badge("MILITARY", Color(0xFF33691E))
                airport.elevationFt?.let {
                    Text(
                        "Elevation ${(it * 0.3048).roundToInt()} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = if (airport.military) 8.dp else 0.dp),
                    )
                }
            }

            metar?.rawOb?.let {
                Text(
                    "Weather" + (metar.fltCat?.let { c -> " · $c" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }

            Section("Arriving", board.arriving, airport, onPick)
            Section("Departed", board.departing, airport, onPick)
            Section("On the ground", board.onGround, airport, onPick)
            if (board.arriving.isEmpty() && board.departing.isEmpty() && board.onGround.isEmpty()) {
                Text(
                    "No tracked flights to, from or at this airport in the loaded area.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Text(
                "Based on aircraft currently tracked and their callsign routes; not an official schedule.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private data class Board(
    val arriving: List<Pair<Aircraft, RouteInfo?>>,
    val departing: List<Pair<Aircraft, RouteInfo?>>,
    val onGround: List<Pair<Aircraft, RouteInfo?>>,
)

@Composable
private fun Section(
    title: String,
    rows: List<Pair<Aircraft, RouteInfo?>>,
    airport: AirportInfo,
    onPick: (String) -> Unit,
) {
    if (rows.isEmpty()) return
    Text(
        "$title (${rows.size})",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    rows.forEach { (a, r) ->
        Row(
            Modifier.fillMaxWidth().clickable { onPick(a.hex) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                val other = when {
                    airport.matches(r?.destination) -> r?.origin?.let { "from ${it.municipality ?: it.code} (${it.code})" }
                    airport.matches(r?.origin) -> r?.destination?.let { "to ${it.municipality ?: it.code} (${it.code})" }
                    else -> null
                }
                Text(
                    listOfNotNull(r?.airlineName, other, a.typeCode).joinToString(" · ").ifEmpty { a.hex.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (a.onGround) "Ground" else a.altitude?.let(Units::altitude) ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "%.0f km".format(distanceKm(airport.lat, airport.lon, a.lat!!, a.lon!!)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * 6371.0 * asin(sqrt(a))
}
