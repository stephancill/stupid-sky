package com.skywatch.tracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * readsb / tar1090 "v2" aircraft JSON, as served by adsb.lol and adsb.fi.
 * Only the fields the app uses are declared; unknown keys are ignored.
 */
@Serializable
data class Aircraft(
    val hex: String,
    val type: String? = null,
    val flight: String? = null,
    @SerialName("r") val registration: String? = null,
    @SerialName("t") val typeCode: String? = null,
    val desc: String? = null,
    val dbFlags: Int = 0,
    // "ground" or a number, so keep it raw.
    @SerialName("alt_baro") val altBaroRaw: JsonElement? = null,
    @SerialName("alt_geom") val altGeom: Int? = null,
    val gs: Double? = null,
    val tas: Double? = null,
    val mach: Double? = null,
    val track: Double? = null,
    @SerialName("true_heading") val trueHeading: Double? = null,
    @SerialName("baro_rate") val baroRate: Int? = null,
    @SerialName("geom_rate") val geomRate: Int? = null,
    val squawk: String? = null,
    val emergency: String? = null,
    val category: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("seen_pos") val seenPos: Double? = null,
    val seen: Double? = null,
    val rssi: Double? = null,
    val mlat: List<String> = emptyList(),
    @SerialName("oat") val outsideAirTemp: Int? = null,
    @SerialName("ws") val windSpeed: Int? = null,
    @SerialName("wd") val windDir: Int? = null,
    @SerialName("nav_altitude_mcp") val selectedAltitude: Int? = null,
    /** Which network this report came from; not part of the JSON. */
    @Transient val source: String = SOURCE_MAIN,
) {
    val fromOpenSky: Boolean get() = source == SOURCE_OPENSKY
    val callsign: String? get() = flight?.trim()?.ifEmpty { null }
    val isMilitary: Boolean get() = dbFlags and 1 != 0
    val isInteresting: Boolean get() = dbFlags and 2 != 0
    val isPia: Boolean get() = dbFlags and 4 != 0
    val isLadd: Boolean get() = dbFlags and 8 != 0
    val onGround: Boolean get() = (altBaroRaw as? JsonPrimitive)?.content == "ground"
    val altitude: Int? get() = (altBaroRaw as? JsonPrimitive)?.doubleOrNull?.toInt() ?: altGeom
    val verticalRate: Int? get() = baroRate ?: geomRate
    val isMlat: Boolean get() = type == "mlat" || mlat.contains("lat")
    val isEmergency: Boolean
        get() = squawk in EMERGENCY_SQUAWKS || (emergency != null && emergency != "none")
    val hasPosition: Boolean get() = lat != null && lon != null
    val label: String get() = callsign ?: registration ?: hex.uppercase()

    companion object {
        val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")
        const val SOURCE_MAIN = "main"
        const val SOURCE_OPENSKY = "opensky"
    }
}

/** adsb.lol uses "ac"; adsb.fi uses "ac" for most endpoints and "aircraft" for area queries. */
@Serializable
data class AircraftResponse(
    val ac: List<Aircraft>? = null,
    val aircraft: List<Aircraft>? = null,
) {
    val all: List<Aircraft> get() = ac ?: aircraft ?: emptyList()
}

// ---- adsbdb.com ----

@Serializable
data class AdsbdbRouteResponse(val response: AdsbdbRouteBody? = null)

@Serializable
data class AdsbdbRouteBody(val flightroute: FlightRoute? = null)

@Serializable
data class FlightRoute(
    val callsign: String? = null,
    @SerialName("callsign_iata") val callsignIata: String? = null,
    val airline: Airline? = null,
    val origin: Airport? = null,
    val destination: Airport? = null,
)

@Serializable
data class AdsbdbAirlineResponse(val response: List<Airline>? = null)

@Serializable
data class Airline(val name: String? = null, val icao: String? = null, val iata: String? = null, val country: String? = null)

@Serializable
data class Airport(
    val name: String? = null,
    val municipality: String? = null,
    @SerialName("iata_code") val iata: String? = null,
    @SerialName("icao_code") val icao: String? = null,
    @SerialName("country_name") val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val code: String get() = iata ?: icao ?: "?"
}

@Serializable
data class AdsbdbAircraftResponse(val response: AdsbdbAircraftBody? = null)

@Serializable
data class AdsbdbAircraftBody(val aircraft: AircraftInfo? = null)

@Serializable
data class AircraftInfo(
    val type: String? = null,
    @SerialName("icao_type") val icaoType: String? = null,
    val manufacturer: String? = null,
    val registration: String? = null,
    @SerialName("registered_owner") val owner: String? = null,
    @SerialName("registered_owner_country_name") val ownerCountry: String? = null,
    @SerialName("registered_owner_operator_flag_code") val operatorCode: String? = null,
    @SerialName("url_photo") val photoUrl: String? = null,
)

// ---- planespotters.net ----

@Serializable
data class PlanespottersResponse(val photos: List<PlanespottersPhoto> = emptyList())

@Serializable
data class PlanespottersPhoto(
    @SerialName("thumbnail_large") val thumbnailLarge: PhotoSrc? = null,
    val thumbnail: PhotoSrc? = null,
    val link: String? = null,
    val photographer: String? = null,
)

@Serializable
data class PhotoSrc(val src: String)

// ---- aviationweather.gov ----

@Serializable
data class Metar(
    val icaoId: String,
    val name: String? = null,
    val rawOb: String? = null,
    val lat: Double,
    val lon: Double,
    val fltCat: String? = null,
)

// ---- rainviewer ----

@Serializable
data class RainViewerMaps(val host: String, val radar: RainViewerRadar)

@Serializable
data class RainViewerRadar(val past: List<RainViewerFrame> = emptyList())

@Serializable
data class RainViewerFrame(val time: Long, val path: String)

/** An airport tapped on the map (from the bundled OurAirports data). */
data class AirportInfo(
    val code: String,
    val icao: String,
    val iata: String?,
    val name: String,
    val city: String?,
    val elevationFt: Int?,
    val lat: Double,
    val lon: Double,
    val military: Boolean,
) {
    /** Matches an adsbdb route airport by ICAO or IATA code. */
    fun matches(a: Airport?): Boolean =
        a != null && ((a.icao != null && a.icao.equals(icao, true)) || (iata != null && a.iata.equals(iata, true)))
}

/** Everything the detail sheet shows beyond the live position report. */
data class Enrichment(
    val route: FlightRoute? = null,
    val info: AircraftInfo? = null,
    val photo: PlanespottersPhoto? = null,
)
