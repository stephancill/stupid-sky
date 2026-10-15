package net.stupidtech.sky.data

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses responses captured from the live APIs (src/test/resources). */
class ParsingTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun adsbLolArea() {
        val r = FlightData.json.decodeFromString<AircraftResponse>(res("adsblol_point.json"))
        assertTrue(r.all.isNotEmpty())
        assertTrue(r.all.any { it.hasPosition && it.altitude != null })
    }

    @Test fun adsbFiAreaUsesAircraftKey() {
        val r = FlightData.json.decodeFromString<AircraftResponse>(res("adsbfi_point.json"))
        assertTrue(r.aircraft!!.isNotEmpty())
        assertEquals(r.aircraft, r.all)
    }

    @Test fun militaryFlagsAndGroundAltitude() {
        val all = FlightData.json.decodeFromString<AircraftResponse>(res("adsbfi_mil.json")).all
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.isMilitary })
        val ground = all.filter { it.onGround }
        assertTrue(ground.isNotEmpty())
        assertTrue(ground.all { it.altitude == null || it.altitude == it.altGeom })
    }

    @Test fun metars() {
        val m = FlightData.json.decodeFromString<List<Metar>>(res("metar.json"))
        assertTrue(m.isNotEmpty())
        assertTrue(m.all { it.rawOb != null })
    }

    @Test fun watchEntryRoundTrip() {
        val e = WatchEntry(WatchKind.MIL_PREFIX, "RCH")
        assertEquals(e, WatchEntry.decode(e.encode()))
    }
}
