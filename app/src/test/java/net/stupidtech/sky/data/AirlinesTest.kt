package net.stupidtech.sky.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class AirlinesTest {
    @Test
    fun mapsAirlineCallsignsFromBundledTable() {
        Airlines.load(File("src/main/assets/airlines.tsv").readText())
        assertEquals("UA", Airlines.iataFor("UAL2830"))
        assertEquals("WN", Airlines.iataFor("SWA371 "))
        assertEquals("U2", Airlines.iataFor("EZY12AB"))
    }

    @Test
    fun ignoresRegistrationsAndUnknownPrefixes() {
        Airlines.load("UAL\tUA\n")
        assertNull(Airlines.iataFor("N494SP"))
        assertNull(Airlines.iataFor("GABCD"))
        assertNull(Airlines.iataFor("ZZZ123"))
    }
}
