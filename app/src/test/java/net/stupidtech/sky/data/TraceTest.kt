package net.stupidtech.sky.data

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun routeFromAnotherLegIsDetected() {
        // SKW5471 (a0b333) flew Spokane -> SFO while adsbdb listed SFO -> Arcata for the callsign.
        // Captured trace trimmed to the end of the previous leg plus the GEG -> SFO leg.
        val leg = Trace.parseTar1090(obj("trace_turnaround.json"))
        val sfo = Airport(iata = "SFO", latitude = 37.6188, longitude = -122.3756)
        val geg = Airport(iata = "GEG", latitude = 47.6199, longitude = -117.5338)
        assertTrue(Trace.contradictsOrigin(leg.first(), sfo))
        assertFalse(Trace.contradictsOrigin(leg.first(), geg))
    }

    @Test fun legFirstSeenAtCruiseIsNotJudged() {
        val sfo = Airport(iata = "SFO", latitude = 37.6188, longitude = -122.3756)
        // Entered coverage mid-flight: the start says nothing about where it took off.
        assertFalse(Trace.contradictsOrigin(LatLon(45.0, -120.0, 35000), sfo))
        assertFalse(Trace.contradictsOrigin(null, sfo))
    }

    @Test fun openSkyTrackParses() {
        val path = Trace.parseOpenSky(obj("track_opensky.json"))
        assertTrue(path.size >= 2)
        assertTrue(path.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
    }
}
