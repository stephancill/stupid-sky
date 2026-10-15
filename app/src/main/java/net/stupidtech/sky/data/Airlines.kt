package net.stupidtech.sky.data

/**
 * ICAO airline prefix → IATA code, bundled from OpenFlights (`tools/build_airlines.py`). Lets logos load
 * from the callsign alone instead of waiting for a per-callsign adsbdb route lookup.
 */
object Airlines {
    @Volatile private var byIcao: Map<String, String> = emptyMap()
    private val AIRLINE_CALLSIGN = Regex("^([A-Z]{3})\\d")

    fun load(tsv: String) {
        byIcao = parse(tsv)
    }

    fun parse(tsv: String): Map<String, String> = tsv.lineSequence()
        .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap()

    /** IATA code for an airline-style callsign (three letters then a digit), else null. */
    fun iataFor(callsign: String): String? =
        AIRLINE_CALLSIGN.find(callsign.trim().uppercase())?.let { byIcao[it.groupValues[1]] }
}

/** The resolved route's airline wins (it reflects codeshares and renames); otherwise use the callsign prefix. */
fun airlineIata(callsign: String, routes: Map<String, RouteInfo>): String? =
    routes[callsign]?.airlineIata ?: Airlines.iataFor(callsign)
