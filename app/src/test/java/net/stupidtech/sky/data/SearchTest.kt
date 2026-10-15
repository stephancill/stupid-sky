package net.stupidtech.sky.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class SearchTest {
    companion object {
        @BeforeClass @JvmStatic fun load() = Airports.load(File("src/main/assets/airports.geojson").readText())
    }

    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun airportCodesMatchExactly() {
        assertEquals("FACT", Airports.byCode("cpt")?.icao)
        assertEquals("EGLL", Airports.byCode("EGLL")?.icao)
        assertNull(Airports.byCode("Cape Town"))
        assertEquals("FACT", Airports.search("CPT").first().icao)
    }

    @Test fun airportNamesAndCitiesMatchByWordBiggestFirst() {
        assertEquals("FACT", Airports.search("cape town").first().icao)
        val london = Airports.search("london")
        assertTrue(london.map { it.icao }.containsAll(listOf("EGLL", "EGKK")))
        assertTrue(Airports.search("heathrow").any { it.icao == "EGLL" })
        assertTrue(Airports.search("x").isEmpty())
    }

    @Test fun parsesPhotonPlaces() {
        val places = Places.parse(fixture("photon_cape_town.json"))
        val city = places.first()
        assertEquals("Cape Town", city.name)
        assertTrue(city.detail.contains("South Africa"))
        assertEquals(-33.93, city.lat, 0.05)
        assertEquals(18.42, city.lon, 0.05)
        val airport = places.first { it.name == "Cape Town International Airport" }
        assertNotNull(airport.extent)
        val box = airport.extent!!
        assertTrue(box.south < box.north && box.west < box.east)
    }
}
