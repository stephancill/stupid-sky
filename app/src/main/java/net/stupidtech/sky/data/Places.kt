package net.stupidtech.sky.data

import kotlinx.serialization.Serializable
import java.net.URLEncoder

/** A geocoded place to fly the map to. [extent] is set for areas (cities, countries), null for points. */
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double, val extent: BoundingBox?)

/**
 * Place search via Photon (photon.komoot.io), an OpenStreetMap geocoder that allows search-as-you-type
 * (Nominatim's usage policy forbids autocomplete). Fair use only, so callers debounce and the throttle
 * keeps requests at least 1 s apart.
 */
object Places {
    suspend fun search(query: String, limit: Int = 5): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return parse(FlightData.fetch("https://photon.komoot.io/api/?q=$q&limit=$limit&lang=en"))
    }

    fun parse(body: String): List<Place> =
        FlightData.json.decodeFromString<PhotonResponse>(body).features.mapNotNull { f ->
            val p = f.properties
            val c = f.geometry.coordinates.takeIf { it.size >= 2 } ?: return@mapNotNull null
            val name = p.name ?: listOfNotNull(p.street?.let { s -> p.housenumber?.let { "$s $it" } ?: s }, p.city)
                .firstOrNull() ?: return@mapNotNull null
            val detail = listOfNotNull(p.city, p.state, p.country).distinct().filter { it != name }.joinToString(", ")
            // Photon's extent is [west, north, east, south].
            val extent = p.extent?.takeIf { it.size == 4 }?.let { BoundingBox(south = it[3], west = it[0], north = it[1], east = it[2]) }
            Place(name, detail, lat = c[1], lon = c[0], extent = extent)
        }.distinctBy { Triple(it.name, it.detail, Math.round(it.lat * 100) to Math.round(it.lon * 100)) } // OSM often has a node and a way for one place
}

@Serializable
private data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
private data class PhotonFeature(val properties: PhotonProperties, val geometry: PhotonGeometry)

@Serializable
private data class PhotonGeometry(val coordinates: List<Double> = emptyList())

@Serializable
private data class PhotonProperties(
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val extent: List<Double>? = null,
)
