package net.stupidtech.sky.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.stupidtech.sky.data.Aircraft
import net.stupidtech.sky.data.Enrichment
import net.stupidtech.sky.data.Metar
import net.stupidtech.sky.data.RouteInfo

@Composable
fun AircraftPanel(
    ac: Aircraft,
    enrichment: Enrichment?,
    routeInfo: RouteInfo?,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uri = LocalUriHandler.current
    // The background resolver usually has the route before the detail lookup finishes.
    val route = enrichment?.route ?: routeInfo?.route
    val info = enrichment?.info
    val photo = enrichment?.photo
    val airline = route?.airline?.name ?: routeInfo?.airlineName ?: info?.owner

    Card(modifier.animateContentSize(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        if (collapsed) {
            CollapsedHeader(ac, airline, route, onExpand = { onCollapsedChange(false) }, onClose = onClose)
            return@Card
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ac.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (airline != null) Text(airline, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { onCollapsedChange(true) }) { Icon(Icons.Default.KeyboardArrowDown, "Minimise") }
                IconButton(onClick = onWatch) { Icon(Icons.Default.NotificationsActive, "Alert me") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ac.isEmergency) Badge("SQUAWK ${ac.squawk ?: "EMERG"}", Color(0xFFD50000))
                if (ac.isMilitary) Badge("MILITARY", Color(0xFF33691E))
                if (ac.isMlat) Badge("MLAT", Color(0xFF455A64))
                if (ac.fromOpenSky) Badge("OPENSKY", Color(0xFF0277BD))
                if (ac.isPia) Badge("PIA", Color(0xFF4A148C))
                if (ac.isLadd) Badge("LADD", Color(0xFF4A148C))
                if (ac.onGround) Badge("ON GROUND", Color(0xFF616161))
            }

            if (route?.origin != null && route.destination != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AirportCol(route.origin.code, route.origin.municipality ?: route.origin.name, Modifier.weight(1f))
                    Text("✈", style = MaterialTheme.typography.headlineSmall)
                    AirportCol(route.destination.code, route.destination.municipality ?: route.destination.name, Modifier.weight(1f), end = true)
                }
                Text(
                    "Route from adsbdb by callsign; may be outdated for irregular flights.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val photoSrc = photo?.thumbnailLarge?.src ?: photo?.thumbnail?.src
            if (photo != null && photoSrc != null) {
                Spacer(Modifier.height(12.dp))
                AsyncImage(
                    model = photoSrc,
                    contentDescription = "Photo of ${ac.registration ?: ac.hex}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(12.dp))
                        .clickable { photo.link?.let(uri::openUri) },
                )
                Text(
                    "© ${photo.photographer ?: "unknown"} · Planespotters.net",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { photo.link?.let(uri::openUri) }.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Grid(
                listOfNotNull(
                    "Altitude" to (if (ac.onGround) "Ground" else ac.altitude?.let(Units::altitude) ?: "—"),
                    "Vertical" to (ac.verticalRate?.let(Units::verticalRate) ?: "—"),
                    "Speed" to (ac.gs?.let(Units::speed) ?: "—"),
                    "Track" to (ac.track?.let { "${it.toInt()}°" } ?: "—"),
                    "Squawk" to (ac.squawk ?: "—"),
                    "Hex" to ac.hex.uppercase(),
                    (route?.airline?.name ?: routeInfo?.airlineName)?.let { "Airline" to it },
                    route?.origin?.let { "From" to airportText(it) },
                    route?.destination?.let { "To" to airportText(it) },
                    "Registration" to (ac.registration ?: info?.registration ?: "—"),
                    "Type" to (ac.typeCode ?: info?.icaoType ?: "—"),
                    (ac.desc ?: info?.let { listOfNotNull(it.manufacturer, it.type).joinToString(" ") })?.takeIf { it.isNotBlank() }?.let { "Aircraft" to it },
                    info?.owner?.let { "Owner" to it },
                    info?.ownerCountry?.let { "Country" to it },
                    ac.tas?.let { "True airspeed" to Units.speed(it) },
                    ac.mach?.let { "Mach" to "%.3f".format(it) },
                    ac.selectedAltitude?.let { "Sel. alt" to Units.altitude(it) },
                    ac.windSpeed?.let { "Wind" to "${ac.windDir ?: "?"}° / ${Units.speed(it.toDouble())}" },
                    ac.outsideAirTemp?.let { "OAT" to "$it °C" },
                    ac.rssi?.let { "Signal" to "%.1f dBFS".format(it) },
                    ac.seen?.let { "Last seen" to if (it < 90) "%.1f s".format(it) else "${(it / 60).toInt()} min" },
                    if (ac.fromOpenSky) "Source" to "OpenSky Network" else null,
                ),
            )
        }
    }
}

/** One-line summary shown when the panel is minimised, so the map and flight path stay visible. */
@Composable
private fun CollapsedHeader(
    ac: Aircraft,
    airline: String?,
    route: net.stupidtech.sky.data.FlightRoute?,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ac.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val o = route?.origin
                val d = route?.destination
                if (o != null && d != null) {
                    Text(
                        "  ${o.code} → ${d.code}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                listOfNotNull(
                    airline,
                    if (ac.onGround) "Ground" else ac.altitude?.let(Units::altitude),
                    ac.gs?.let(Units::speed),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onExpand) { Icon(Icons.Default.KeyboardArrowUp, "Expand") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
    }
}

@Composable
fun MetarPanel(m: Metar, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.icaoId, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    m.name?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                m.fltCat?.let {
                    Badge(
                        it,
                        when (it) {
                            "VFR" -> Color(0xFF2E7D32); "MVFR" -> Color(0xFF1565C0)
                            "IFR" -> Color(0xFFC62828); else -> Color(0xFFAD1457)
                        },
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            Spacer(Modifier.height(8.dp))
            Text(m.rawOb ?: "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Source: NOAA aviationweather.gov",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun airportText(a: net.stupidtech.sky.data.Airport): String =
    listOfNotNull(a.municipality ?: a.name, "(${a.code})").joinToString(" ")

@Composable
private fun AirportCol(code: String, city: String?, modifier: Modifier, end: Boolean = false) {
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        city?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Grid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row {
                row.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
