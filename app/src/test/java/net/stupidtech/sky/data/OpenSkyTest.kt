package net.stupidtech.sky.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a real OpenSky response captured around Cape Town International. */
class OpenSkyTest {
    private val root = FlightData.json.parseToJsonElement(
        javaClass.classLoader!!.getResource("opensky_cpt.json")!!.readText(),
    ) as JsonObject
    private val time = root["time"]!!.jsonPrimitive.long
    private val aircraft = root["states"]!!.jsonArray.mapNotNull { OpenSky.toAircraft(it as JsonArray, time) }

    @Test fun parsesAllStatesWithPositions() {
        assertTrue(aircraft.isNotEmpty())
        assertTrue(aircraft.all { it.hasPosition && it.fromOpenSky })
    }

    @Test fun groundAircraftAtCptIsOnGround() {
        val sfr = aircraft.first { it.callsign == "SFR311" }
        assertTrue(sfr.onGround)
        assertEquals(null, sfr.altitude)
        assertNotNull(sfr.seenPos)
    }

    @Test fun convertsUnitsToFeetAndKnots() {
        val a = aircraft.first { it.callsign == "ZSOCI" }
        // 2415.54 m baro, 71.55 m/s
        assertEquals(7925, a.altitude)
        assertEquals(139.1, a.gs!!, 0.2)
    }

    @Test fun boxIsCroppedToOneCreditSize() {
        val b = BoundingBox(-40.0, 10.0, -20.0, 30.0).cropAroundCentre(5.0)
        assertEquals(5.0, b.north - b.south, 1e-9)
        assertEquals(5.0, b.east - b.west, 1e-9)
        assertEquals(-30.0, (b.north + b.south) / 2, 1e-9)
    }

    @Test fun pollIntervalRespectsMinimumsAndBudget() {
        assertTrue(OpenSky.pollIntervalSec(null, hasAccount = false) == 300L)
        assertTrue(OpenSky.pollIntervalSec(null, hasAccount = true) == 30L)
        // 1 credit left must wait until reset.
        assertTrue(OpenSky.pollIntervalSec(1, hasAccount = true) >= OpenSky.secondsUntilReset() - 1)
    }
}
