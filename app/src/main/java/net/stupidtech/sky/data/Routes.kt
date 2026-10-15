package net.stupidtech.sky.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.util.concurrent.ConcurrentHashMap

/** What we know about a flight beyond its position: airline and origin → destination. */
data class RouteInfo(val route: FlightRoute?, val airline: Airline?) {
    val origin: Airport? get() = route?.origin
    val destination: Airport? get() = route?.destination
    val airlineName: String? get() = airline?.name ?: route?.airline?.name
    val hasRoute: Boolean get() = origin != null && destination != null
    /** 2-letter IATA airline code, used for the logo. */
    val airlineIata: String? get() = (airline?.iata ?: route?.airline?.iata)?.takeIf { it.length == 2 }
}

/**
 * Looks up routes by callsign (adsbdb.com), one at a time through the shared per-host throttle,
 * and caches results, including "unknown", so each callsign is only asked about once.
 */
object Routes {
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private val AIRLINE_CALLSIGN = Regex("^([A-Z]{3})\\d")

    private data class Entry(val info: RouteInfo, val at: Long)

    private val byCallsign = ConcurrentHashMap<String, Entry>()
    private val airlines = ConcurrentHashMap<String, Airline>()
    private val unknownAirlines = ConcurrentHashMap.newKeySet<String>()

    fun cached(callsign: String): RouteInfo? =
        byCallsign[callsign]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.info

    fun isCached(callsign: String) = cached(callsign) != null

    /**
     * Route + airline for [callsign]. Returns null on a transient failure (network, rate limit)
     * so it can be retried later; "unknown callsign" is cached like any other answer.
     */
    suspend fun resolve(callsign: String): RouteInfo? {
        cached(callsign)?.let { return it }
        val route = try {
            FlightData.get<AdsbdbRouteResponse>("https://api.adsbdb.com/v0/callsign/$callsign").response?.flightroute
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotFoundException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: Exception) {
            return null
        }
        val airline = route?.airline ?: airlineFor(callsign)
        return RouteInfo(route, airline).also {
            byCallsign[callsign] = Entry(it, System.currentTimeMillis())
        }
    }

    /** Airline from the callsign's 3-letter ICAO prefix (e.g. SAA123 → South African Airways). */
    private suspend fun airlineFor(callsign: String): Airline? {
        val icao = AIRLINE_CALLSIGN.find(callsign)?.groupValues?.get(1) ?: return null
        airlines[icao]?.let { return it }
        if (icao in unknownAirlines) return null
        val a = try {
            FlightData.get<AdsbdbAirlineResponse>("https://api.adsbdb.com/v0/airline/$icao").response?.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            null // adsbdb answers unknown airlines with a plain string
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            return null // transient: don't remember as unknown
        }
        if (a != null) airlines[icao] = a else unknownAirlines += icao
        return a
    }
}
