package com.skywatch.tracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

/**
 * OpenSky Network, used on top of the main feed to fill coverage gaps (e.g. aircraft on the
 * ground at airports without a nearby adsb.lol / adsb.fi feeder).
 *
 * Free tier only: anonymous requests get a small daily credit allowance; a free account
 * (OAuth2 client credentials) gets ~10x more. A bounding box up to 25 sq° costs 1 credit,
 * so queries are kept to that size. Non-commercial use only.
 */
object OpenSky {
    private const val STATES_URL = "https://opensky-network.org/api/states/all"
    private const val TOKEN_URL =
        "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token"

    /** Largest box that still costs a single credit. */
    private const val MAX_BOX_DEG = 5.0

    data class Result(val aircraft: List<Aircraft>, val creditsRemaining: Int?)

    class RateLimited(val retryAfterSec: Long?) : IOException("OpenSky daily credits used up")

    private var token: String? = null
    private var tokenExpiresAt = 0L
    private var tokenFor: String? = null

    /** States within the viewport (cropped to ≤ 5°×5° around its centre). */
    suspend fun states(v: BoundingBox, creds: OpenSkyCredentials?): Result = withContext(Dispatchers.IO) {
        val box = v.cropAroundCentre(MAX_BOX_DEG)
        val url = String.format(
            Locale.US, "%s?lamin=%.4f&lomin=%.4f&lamax=%.4f&lomax=%.4f&extended=1",
            STATES_URL, box.south, box.west, box.north, box.east,
        )
        val req = Request.Builder().url(url)
        bearer(creds)?.let { req.header("Authorization", "Bearer $it") }

        FlightData.http.newCall(req.build()).execute().use { resp ->
            if (resp.code == 429) {
                throw RateLimited(resp.header("X-Rate-Limit-Retry-After-Seconds")?.toLongOrNull())
            }
            if (resp.code == 401) {
                token = null
                throw IOException("OpenSky rejected the client ID/secret")
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from OpenSky")
            val body = resp.body?.string() ?: throw IOException("Empty response from OpenSky")
            val parsed = FlightData.json.decodeFromString<StatesResponse>(body)
            Result(
                aircraft = parsed.states.orEmpty().mapNotNull { toAircraft(it, parsed.time) },
                creditsRemaining = resp.header("X-Rate-Limit-Remaining")?.toIntOrNull(),
            )
        }
    }

    /**
     * Seconds to wait between polls so the remaining credits last until they reset at
     * midnight UTC, never faster than [minSec].
     */
    fun pollIntervalSec(creditsRemaining: Int?, hasAccount: Boolean): Long {
        val minSec = if (hasAccount) 30L else 300L
        val left = creditsRemaining ?: return minSec
        if (left <= 0) return secondsUntilReset()
        return maxOf(minSec, secondsUntilReset() / left)
    }

    fun secondsUntilReset(): Long {
        val now = System.currentTimeMillis() / 1000
        val midnight = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return (midnight - now).coerceAtLeast(60)
    }

    private fun bearer(creds: OpenSkyCredentials?): String? {
        if (creds == null || !creds.isSet) return null
        val key = creds.clientId
        val now = System.currentTimeMillis()
        if (token != null && tokenFor == key && now < tokenExpiresAt) return token
        val form = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", creds.clientId)
            .add("client_secret", creds.clientSecret)
            .build()
        FlightData.http.newCall(Request.Builder().url(TOKEN_URL).post(form).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("OpenSky login failed (HTTP ${resp.code}) — check client ID/secret")
            val t = FlightData.json.decodeFromString<TokenResponse>(resp.body!!.string())
            token = t.accessToken
            tokenFor = key
            // Refresh a minute early.
            tokenExpiresAt = now + (t.expiresIn - 60).coerceAtLeast(60) * 1000L
            return token
        }
    }

    /**
     * OpenSky state vector (array):
     * 0 icao24, 1 callsign, 2 country, 3 time_position, 4 last_contact, 5 lon, 6 lat,
     * 7 baro_altitude m, 8 on_ground, 9 velocity m/s, 10 true_track, 11 vertical_rate m/s,
     * 12 sensors, 13 geo_altitude m, 14 squawk, 15 spi, 16 position_source, 17 category.
     */
    internal fun toAircraft(s: JsonArray, now: Long): Aircraft? {
        fun at(i: Int): JsonPrimitive? = s.getOrNull(i)?.takeIf { it !is JsonNull } as? JsonPrimitive
        val hex = at(0)?.content?.lowercase() ?: return null
        val lon = at(5)?.doubleOrNull ?: return null
        val lat = at(6)?.doubleOrNull ?: return null
        val onGround = at(8)?.booleanOrNull == true
        val baroFt = at(7)?.doubleOrNull?.let { (it * M_TO_FT).roundToInt() }
        val geoFt = at(13)?.doubleOrNull?.let { (it * M_TO_FT).roundToInt() }
        val lastContact = at(4)?.longOrNull
        val lastPos = at(3)?.longOrNull
        val alt: JsonElement? = when {
            onGround -> JsonPrimitive("ground")
            baroFt != null -> JsonPrimitive(baroFt)
            else -> null
        }
        return Aircraft(
            hex = hex,
            type = if (at(16)?.intOrNull == 2) "mlat" else "opensky",
            flight = at(1)?.content,
            altBaroRaw = alt,
            altGeom = geoFt,
            gs = at(9)?.doubleOrNull?.times(MS_TO_KT),
            track = at(10)?.doubleOrNull,
            baroRate = at(11)?.doubleOrNull?.times(MS_TO_FPM)?.roundToInt(),
            squawk = at(14)?.content,
            category = if (at(17)?.intOrNull == 8) "A7" else null,
            lat = lat,
            lon = lon,
            seen = lastContact?.let { (now - it).toDouble().coerceAtLeast(0.0) },
            seenPos = lastPos?.let { (now - it).toDouble().coerceAtLeast(0.0) },
            source = Aircraft.SOURCE_OPENSKY,
        )
    }

    private const val M_TO_FT = 3.28084
    private const val MS_TO_KT = 1.943844
    private const val MS_TO_FPM = 196.8504

    @Serializable
    private data class StatesResponse(val time: Long, val states: List<JsonArray>? = null)

    @Serializable
    private data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String,
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long = 1800,
    )
}

data class OpenSkyCredentials(val clientId: String, val clientSecret: String) {
    val isSet get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun cropAroundCentre(maxDeg: Double): BoundingBox {
        val cLat = (south + north) / 2
        val cLon = (west + east) / 2
        val halfLat = minOf((north - south) / 2, maxDeg / 2)
        val halfLon = minOf((east - west) / 2, maxDeg / 2)
        return BoundingBox(cLat - halfLat, cLon - halfLon, cLat + halfLat, cLon + halfLon)
    }
}
