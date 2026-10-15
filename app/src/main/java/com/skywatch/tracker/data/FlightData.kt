package com.skywatch.tracker.data

import com.skywatch.tracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Community ADS-B networks with free, unfiltered public APIs. All are queried and merged. */
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

class RateLimitedException(val host: String, val retryAfterSec: Long?) :
    IOException("Rate limited by $host")

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

    /** Result of querying every network; [failed] lists networks that errored or were skipped. */
    data class Aggregate(val aircraft: List<Aircraft>, val failed: Set<Provider>)

    /** Per-network cooldown after a 429, so one busy network doesn't hold up the other. */
    private val cooldownUntil = ConcurrentHashMap<Provider, Long>()
    private val cooldownMs = ConcurrentHashMap<Provider, Long>()

    /**
     * Queries all networks in parallel and merges by ICAO hex. Throws only if every network
     * failed (RateLimitedException if they were all rate limited).
     */
    private suspend fun aggregate(path: (Provider) -> String): Aggregate = coroutineScope {
        val now = System.currentTimeMillis()
        val results = Provider.entries.map { p ->
            p to async {
                if ((cooldownUntil[p] ?: 0L) > now) {
                    Result.failure(RateLimitedException(p.label, ((cooldownUntil[p]!! - now) / 1000)))
                } else {
                    runCatching { get<AircraftResponse>(path(p)).all }.onSuccess {
                        cooldownMs.remove(p)
                    }.onFailure { e ->
                        if (e is RateLimitedException) {
                            // Honour Retry-After, else back off 20 s, 40 s, 80 s … up to 5 min.
                            val next = e.retryAfterSec?.times(1000)
                                ?: ((cooldownMs[p] ?: 10_000L) * 2).coerceAtMost(300_000L)
                            cooldownMs[p] = next
                            cooldownUntil[p] = System.currentTimeMillis() + next
                        }
                    }
                }
            }
        }.map { (p, d) -> p to d.await() }

        val ok = results.filter { it.second.isSuccess }
        if (ok.isEmpty()) {
            val errors = results.mapNotNull { it.second.exceptionOrNull() }
            val limited = errors.filterIsInstance<RateLimitedException>()
            if (limited.size == errors.size) {
                throw RateLimitedException(
                    limited.joinToString(" & ") { it.host },
                    limited.mapNotNull { it.retryAfterSec }.minOrNull(),
                )
            }
            throw errors.firstOrNull { it !is RateLimitedException } ?: IOException("No data")
        }
        Aggregate(
            aircraft = merge(ok.map { it.second.getOrThrow() }),
            failed = results.filter { it.second.isFailure }.mapTo(HashSet()) { it.first },
        )
    }

    /** Keeps the freshest position per aircraft, filling missing details from the other report. */
    internal fun merge(lists: List<List<Aircraft>>): List<Aircraft> {
        val byHex = LinkedHashMap<String, Aircraft>()
        for (list in lists) for (a in list) {
            val b = byHex[a.hex]
            byHex[a.hex] = if (b == null) a else {
                val (fresh, other) = if ((a.seenPos ?: 999.0) <= (b.seenPos ?: 999.0)) a to b else b to a
                fresh.copy(
                    registration = fresh.registration ?: other.registration,
                    typeCode = fresh.typeCode ?: other.typeCode,
                    desc = fresh.desc ?: other.desc,
                    flight = fresh.flight ?: other.flight,
                    dbFlags = fresh.dbFlags or other.dbFlags,
                    squawk = fresh.squawk ?: other.squawk,
                )
            }
        }
        return byHex.values.toList()
    }

    suspend fun area(lat: Double, lon: Double, radiusNm: Int): Aggregate {
        val r = radiusNm.coerceIn(1, Provider.MAX_RADIUS_NM)
        return aggregate { it.area(lat, lon, r) }
    }

    suspend fun military(): Aggregate = aggregate { it.military() }

    /** Search by hex (6 hex chars), then registration, then callsign, across all networks. */
    suspend fun search(query: String): List<Aircraft> {
        val q = query.trim().replace(" ", "")
        if (q.isEmpty()) return emptyList()
        if (HEX.matches(q)) {
            val r = runCatching { aggregate { it.hex(q) }.aircraft }.getOrDefault(emptyList())
            if (r.isNotEmpty()) return r
        }
        val byReg = runCatching { aggregate { it.registration(q) }.aircraft }.getOrDefault(emptyList())
        if (byReg.isNotEmpty()) return byReg
        return runCatching { aggregate { it.callsign(q) }.aircraft }.getOrDefault(emptyList())
    }

    suspend fun lookup(kind: WatchKind, value: String): List<Aircraft> = when (kind) {
        WatchKind.HEX -> aggregate { it.hex(value) }.aircraft
        WatchKind.REG -> aggregate { it.registration(value) }.aircraft
        WatchKind.CALLSIGN -> aggregate { it.callsign(value) }.aircraft
        WatchKind.MIL_PREFIX -> military().aircraft.filter {
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
        val request = Request.Builder().url(url).build()
        val host = request.url.host
        Throttle.acquire(host)
        http.newCall(request).await().use { resp ->
            if (resp.code == 429) {
                val retryAfter = resp.header("Retry-After")?.toLongOrNull()
                throw RateLimitedException(host, retryAfter)
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from $host")
            resp.body?.string() ?: throw IOException("Empty response from $host")
        }
    }

    /** Serialises requests per host and keeps a minimum gap between them. */
    private object Throttle {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val last = ConcurrentHashMap<String, Long>()

        private fun gapFor(host: String): Long = when {
            host.endsWith("adsb.fi") -> 1_100   // documented 1 req/s
            host.endsWith("adsb.lol") -> 1_000
            host.endsWith("adsbdb.com") -> 500
            host.endsWith("planespotters.net") -> 500
            else -> 250
        }

        suspend fun acquire(host: String) {
            locks.getOrPut(host) { Mutex() }.withLock {
                val wait = (last[host] ?: 0L) + gapFor(host) - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                last[host] = System.currentTimeMillis()
            }
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
