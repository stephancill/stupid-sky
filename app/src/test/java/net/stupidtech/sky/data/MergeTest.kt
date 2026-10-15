package net.stupidtech.sky.data

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun freshestPositionWinsAndGapsAreFilled() {
        val stale = Aircraft(hex = "abc123", lat = 1.0, lon = 1.0, seenPos = 8.0, registration = "ZS-ABC", dbFlags = 1)
        val fresh = Aircraft(hex = "abc123", lat = 2.0, lon = 2.0, seenPos = 0.5, desc = "BOEING 737-800")
        val merged = FlightData.merge(listOf(listOf(stale), listOf(fresh))).single()
        assertEquals(2.0, merged.lat!!, 0.0)
        assertEquals("ZS-ABC", merged.registration)
        assertEquals("BOEING 737-800", merged.desc)
        assertTrue(merged.isMilitary)
    }

    @Test fun realFeedsMergeWithoutDuplicates() {
        val lol = FlightData.json.decodeFromString<AircraftResponse>(res("adsblol_point.json")).all
        val fi = FlightData.json.decodeFromString<AircraftResponse>(res("adsbfi_point.json")).all
        val merged = FlightData.merge(listOf(lol, fi))
        assertEquals(merged.size, merged.map { it.hex }.toSet().size)
        assertTrue(merged.size >= maxOf(lol.size, fi.size))
        assertEquals((lol + fi).map { it.hex }.toSet().size, merged.size)
    }
}
