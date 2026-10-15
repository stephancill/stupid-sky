package com.skywatch.tracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UnitsTest {
    init { Locale.setDefault(Locale.US) }

    @Test fun altitudeInMetresRoundedTo10() = assertEquals("10,670 m", Units.altitude(35000))
    @Test fun speedInKmh() = assertEquals("833 km/h", Units.speed(450.0))
    @Test fun climbInMetresPerSecond() = assertEquals("▲ 10.2 m/s", Units.verticalRate(2000))
    @Test fun descentArrow() = assertEquals("▼ 5.1 m/s", Units.verticalRate(-1000))
    @Test fun shortDistanceHasDecimal() = assertEquals("3.7 km", Units.distance(2.0))
    @Test fun longDistanceWhole() = assertEquals("185 km", Units.distance(100.0))
}
