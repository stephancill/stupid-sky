package com.skywatch.tracker.data

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceTest {
    private fun obj(name: String) =
        FlightData.json.parseToJsonElement(javaClass.classLoader!!.getResource(name)!!.readText()).jsonObject

    @Test fun adsbLolTraceIsCutToCurrentLeg() {
        val root = obj("trace_adsblol.json")
        val all = root["trace"]!!.toString().count { it == '[' } // rough size of the whole day
        val leg = Trace.parseTar1090(root)
        assertTrue(leg.size >= 2)
        // The captured day has two flights; the new-leg flag is at row 351 of 534.
        assertEquals(534 - 351, leg.size)
        assertTrue(leg.size < all)
    }

    @Test fun openSkyTrackParses() {
        val path = Trace.parseOpenSky(obj("track_opensky.json"))
        assertTrue(path.size >= 2)
        assertTrue(path.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
    }
}
