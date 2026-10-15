package com.skywatch.tracker.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

/** A point on a flight path. [alt] in feet, null when on the ground or unknown. */
data class LatLon(val lat: Double, val lon: Double, val alt: Int?)

/**
 * Flown path of the aircraft's current flight.
 * 1. adsb.lol trace history (free, no quota): today's full track, cut to the current leg.
 * 2. OpenSky /tracks (free tier) as a fallback.
 */
object Trace {
    /** Gap that separates two flights when the feed didn't mark a new leg. */
    private const val LEG_GAP_SEC = 30 * 60

    suspend fun currentFlight(hex: String, openSkyCreds: OpenSkyCredentials?): List<LatLon> {
        val h = hex.lowercase()
        try {
            val body = FlightData.fetch("https://adsb.lol/data/traces/${h.takeLast(2)}/trace_full_$h.json")
            val pts = parseTar1090(FlightData.json.parseToJsonElement(body).jsonObject)
            if (pts.size >= 2) return pts
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // fall through to OpenSky
        }
        return try {
            val body = FlightData.fetch("https://opensky-network.org/api/tracks/all?icao24=$h&time=0")
            parseOpenSky(FlightData.json.parseToJsonElement(body).jsonObject)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * tar1090 trace: each row is [secondsSinceTimestamp, lat, lon, alt|"ground"|null, gs, track, flags, …].
     * flags bit 1 (value 2) marks the start of a new leg.
     */
    internal fun parseTar1090(root: JsonObject): List<LatLon> {
        val rows = root["trace"]?.jsonArray?.mapNotNull { it as? JsonArray } ?: return emptyList()
        if (rows.isEmpty()) return emptyList()
        var start = 0
        for (i in rows.indices.reversed()) {
            val flags = (rows[i].getOrNull(6) as? JsonPrimitive)?.intOrNull ?: 0
            val gap = if (i > 0) rows[i].sec() - rows[i - 1].sec() else 0.0
            if (flags and 2 != 0 || gap > LEG_GAP_SEC) {
                start = i
                break
            }
        }
        return rows.subList(start, rows.size).mapNotNull { r ->
            val lat = (r.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lon = (r.getOrNull(2) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val alt = (r.getOrNull(3) as? JsonPrimitive)?.intOrNull // "ground" → null
            LatLon(lat, lon, alt)
        }
    }

    /** OpenSky track: path rows are [time, lat, lon, baro_altitude m, true_track, on_ground]. */
    internal fun parseOpenSky(root: JsonObject): List<LatLon> =
        root["path"]?.jsonArray?.mapNotNull { el ->
            val r = el as? JsonArray ?: return@mapNotNull null
            val lat = r[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val lon = r[2].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val onGround = r.getOrNull(5)?.jsonPrimitive?.booleanOrNull == true
            val altM = r[3].jsonPrimitive.doubleOrNull
            LatLon(lat, lon, if (onGround) null else altM?.let { (it * 3.28084).roundToInt() })
        }.orEmpty()

    private fun JsonArray.sec(): Double = (getOrNull(0) as? JsonPrimitive)?.doubleOrNull
        ?: (getOrNull(0) as? JsonPrimitive)?.longOrNull?.toDouble() ?: 0.0
}
