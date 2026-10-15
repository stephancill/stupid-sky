package com.skywatch.tracker.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AirportsTest {
    private val features = FlightData.json
        .parseToJsonElement(File("src/main/assets/airports.geojson").readText())
        .jsonObject["features"]!!.jsonArray.map { it.jsonObject["properties"] as JsonObject }

    private fun byIcao(icao: String) = features.first { it["icao"]!!.jsonPrimitive.content == icao }

    @Test fun bundledDataHasCapeTownAirports() {
        val cpt = byIcao("FACT")
        assertEquals("CPT", cpt["code"]!!.jsonPrimitive.content)
        assertEquals(3, cpt["rank"]!!.jsonPrimitive.int)
        assertTrue(byIcao("FAYP")["mil"]!!.jsonPrimitive.boolean) // Ysterplaat AFB
        assertTrue(features.size > 10_000)
    }

    @Test fun matchesRouteAirportByIcaoOrIata() {
        val apt = AirportInfo("CPT", "FACT", "CPT", "Cape Town International", "Cape Town", 151, -33.97, 18.60, false)
        assertTrue(apt.matches(Airport(icao = "FACT")))
        assertTrue(apt.matches(Airport(iata = "cpt")))
        assertFalse(apt.matches(Airport(icao = "FALA", iata = "HLA")))
        assertFalse(apt.matches(null))
    }
}
