package net.stupidtech.sky.data

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Search over the bundled OurAirports data (`assets/airports.geojson`, the same file the map draws).
 * Offline and instant, so it runs on every keystroke.
 */
object Airports {
    private class Entry(val info: AirportInfo, val rank: Int, val haystack: String)

    @Volatile private var entries: List<Entry> = emptyList()

    fun load(geojson: String) {
        entries = parse(geojson)
    }

    private fun parse(geojson: String): List<Entry> =
        FlightData.json.parseToJsonElement(geojson).jsonObject["features"]!!.jsonArray.mapNotNull { f ->
            val p = f.jsonObject["properties"]!!.jsonObject
            val c = f.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            fun str(k: String) = p[k]?.jsonPrimitive?.contentOrNull
            val info = AirportInfo(
                code = str("code") ?: return@mapNotNull null,
                icao = str("icao") ?: return@mapNotNull null,
                iata = str("iata"),
                name = str("name") ?: "",
                city = str("city"),
                elevationFt = p["elev"]?.jsonPrimitive?.intOrNull,
                lat = c[1].jsonPrimitive.double,
                lon = c[0].jsonPrimitive.double,
                military = p["mil"]?.jsonPrimitive?.booleanOrNull == true,
            )
            Entry(info, p["rank"]?.jsonPrimitive?.intOrNull ?: 0, "${info.name} ${info.city.orEmpty()}".lowercase())
        }

    /** Airport whose IATA or ICAO code is exactly [query], preferring the bigger one. */
    fun byCode(query: String): AirportInfo? {
        val q = query.trim()
        if (q.length !in 3..4) return null
        return entries.filter { it.info.iata.equals(q, true) || it.info.icao.equals(q, true) }
            .maxByOrNull { it.rank }?.info
    }

    /**
     * Code matches first, then names or cities where a word starts with the query, then any substring;
     * bigger airports first within each group.
     */
    fun search(query: String, limit: Int = 5): List<AirportInfo> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        fun score(e: Entry): Int = when {
            e.info.iata.equals(q, true) || e.info.icao.equals(q, true) -> 0
            e.haystack.startsWith(q) || e.haystack.contains(" $q") || e.haystack.contains("($q") -> 1
            e.haystack.contains(q) -> 2
            else -> -1
        }
        return entries.asSequence()
            .map { it to score(it) }
            .filter { it.second >= 0 }
            .sortedWith(compareBy<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.rank })
            .take(limit)
            .map { it.first.info }
            .toList()
    }
}
