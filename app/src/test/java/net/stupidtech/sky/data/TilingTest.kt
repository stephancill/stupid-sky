package net.stupidtech.sky.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class TilingTest {
    private fun nm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 3440.065 * asin(sqrt(a))
    }

    /** Every point on a fine grid over the box is inside some circle. */
    private fun assertCovers(box: BoundingBox, t: Tiling) {
        for (i in 0..40) for (j in 0..40) {
            val lat = box.south + (box.north - box.south) * i / 40
            val lon = box.west + (box.east - box.west) * j / 40
            assertTrue("($lat, $lon) uncovered", t.circles.any { nm(lat, lon, it.lat, it.lon) <= it.radiusNm })
        }
    }

    @Test fun southAfricaNeedsSeveralTilesAndIsFullyCovered() {
        val sa = BoundingBox(south = -35.0, west = 16.0, north = -22.0, east = 33.0)
        val t = Tiles.cover(sa)
        assertFalse(t.cropped)
        assertTrue(t.circles.size in 4..Tiles.MAX)
        assertTrue(t.circles.all { it.radiusNm <= Provider.MAX_RADIUS_NM })
        assertCovers(sa, t)
    }

    @Test fun smallViewIsOneCircle() {
        val t = Tiles.cover(BoundingBox(-34.5, 18.0, -33.5, 19.0))
        assertEquals(1, t.circles.size)
        assertTrue(t.circles.single().radiusNm < 60)
    }

    @Test fun hugeViewIsCroppedToTheLimit() {
        val t = Tiles.cover(BoundingBox(-60.0, -170.0, 70.0, 170.0))
        assertTrue(t.cropped)
        assertTrue(t.circles.size <= Tiles.MAX)
    }

    @Test fun boxAcrossTheEquatorIsCovered() {
        val box = BoundingBox(-8.0, 30.0, 8.0, 42.0)
        assertCovers(box, Tiles.cover(box))
    }
}
