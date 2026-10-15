package com.skywatch.tracker.data

import com.skywatch.tracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Community ADS-B networks with free, unfiltered public APIs. */
enum class Provider(val label: String, val baseUrl: String) {
    ADSB_LOL("adsb.lol", "https://api.adsb.lol/v2"),
    ADSB_FI("adsb.fi", "https://opendata.adsb.fi/api/v2");

    fun area(lat: Double, lon: Double, radiusNm: Int): String = when (this) {
        ADSB_LOL -> "$baseUrl/point/${lat.f()}/${lon.f()}/$radiusNm"
        ADSB_FI -> "$baseUrl/lat/${lat.f()}/lon/${lon.f()}/dist/$radiusNm"
    }

    fun military(): String = "$baseUrl/mil"
    fun hex(hex: String): String = "$baseUrl/hex/${hex.lowercase()}"
    fun callsign(cs: String): String = "$baseUrl/callsign/${cs.uppercase()}"
    fun registration(reg: String): String = when (this) {
        ADSB_LOL -> "$baseUrl/reg/${reg.uppercase()}"
        ADSB_FI -> "$baseUrl/registration/${reg.uppercase()}"
    }

    private fun Double.f() = String.format(Locale.US, "%.4f", this)

    companion object {
        /** Both networks cap area queries at 250 nm. */
        const val MAX_RADIUS_NM = 250
    }
}

object FlightData {
    private val userAgent = "SkyWatch/${BuildConfig.VERSION_NAME} (+${BuildConfig.CONTACT})"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val enrichmentCache = ConcurrentHashMap<String, Enrichment>()

    suspend fun area(p: Provider, lat: Double, lon: Double, radiusNm: Int): List<Aircraft> =
        get<AircraftResponse>(p.area(lat, lon, radiusNm.coerceIn(1, Provider.MAX_RADIUS_NM))).all

    suspend fun military(p: Provider): List<Aircraft> = get<AircraftResponse>(p.military()).all

    /** Search by hex (6 hex chars), then registration, then callsign. */
    suspend fun search(p: Provider, query: String): List<Aircraft> {
        val q = query.trim().replace(" ", "")
        if (q.isEmpty()) return emptyList()
        if (HEX.matches(q)) {
            val r = runCatching { get<AircraftResponse>(p.hex(q)).all }.getOrDefault(emptyList())
            if (r.isNotEmpty()) return r
        }
        val byReg = runCatching { get<AircraftResponse>(p.registration(q)).all }.getOrDefault(emptyList())
        if (byReg.isNotEmpty()) return byReg
        return runCatching { get<AircraftResponse>(p.callsign(q)).all }.getOrDefault(emptyList())
    }

    suspend fun lookup(p: Provider, kind: WatchKind, value: String): List<Aircraft> = when (kind) {
        WatchKind.HEX -> get<AircraftResponse>(p.hex(value)).all
        WatchKind.REG -> get<AircraftResponse>(p.registration(value)).all
        WatchKind.CALLSIGN -> get<AircraftResponse>(p.callsign(value)).all
        WatchKind.MIL_PREFIX -> military(p).filter {
            it.callsign?.startsWith(value, ignoreCase = true) == true
        }
    }

    /** Route (adsbdb), owner/type (adsbdb) and photo (planespotters), fetched in parallel. */
    suspend fun enrich(ac: Aircraft): Enrichment {
        val key = "${ac.hex}|${ac.callsign}"
        enrichmentCache[key]?.let { return it }
        val result = coroutineScope {
            val route = async {
                ac.callsign?.let { cs ->
                    runCatching { get<AdsbdbRouteResponse>("https://api.adsbdb.com/v0/callsign/$cs") }
                        .getOrNull()?.response?.flightroute
                }
            }
            val info = async {
                runCatching { get<AdsbdbAircraftResponse>("https://api.adsbdb.com/v0/aircraft/${ac.hex}") }
                    .getOrNull()?.response?.aircraft
            }
            val photo = async {
                runCatching { get<PlanespottersResponse>("https://api.planespotters.net/pub/photos/hex/${ac.hex}") }
                    .getOrNull()?.photos?.firstOrNull()
                    ?: ac.registration?.let { reg ->
                        runCatching { get<PlanespottersResponse>("https://api.planespotters.net/pub/photos/reg/$reg") }
                            .getOrNull()?.photos?.firstOrNull()
                    }
            }
            Enrichment(route.await(), info.await(), photo.await())
        }
        enrichmentCache[key] = result
        return result
    }

    suspend fun metars(south: Double, west: Double, north: Double, east: Double): List<Metar> {
        val bbox = String.format(Locale.US, "%.2f,%.2f,%.2f,%.2f", south, west, north, east)
        return get("https://aviationweather.gov/api/data/metar?bbox=$bbox&format=json")
    }

    /** Latest RainViewer radar frame as a XYZ tile template. */
    suspend fun radarTileUrl(): String? {
        val maps = get<RainViewerMaps>("https://api.rainviewer.com/public/weather-maps.json")
        val frame = maps.radar.past.lastOrNull() ?: return null
        return "${maps.host}${frame.path}/256/{z}/{x}/{y}/2/1_1.png"
    }

    suspend inline fun <reified T> get(url: String): T {
        val body = fetch(url)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(body) }
    }

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val call = http.newCall(Request.Builder().url(url).build())
        call.await().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            resp.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        })
        cont.invokeOnCancellation { cancel() }
    }

    private val HEX = Regex("^[0-9a-fA-F]{6}$")
}
