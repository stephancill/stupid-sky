package net.stupidtech.sky.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetStopTest {
    // y offsets of the sheet's top edge on a 2000 px tall screen.
    private val stops = listOf(
        SheetStop.Hidden to 2000f,
        SheetStop.Peek to 1800f,
        SheetStop.Half to 1000f,
        SheetStop.Full to 200f,
    )

    @Test fun slowReleaseSettlesOnNearestStop() {
        assertEquals(SheetStop.Half, pickStop(stops, 1300f, velocity = 50f, velocityThreshold = 1000f))
        assertEquals(SheetStop.Peek, pickStop(stops, 1500f, velocity = -50f, velocityThreshold = 1000f))
    }

    @Test fun flingMovesOneStopInItsDirection() {
        // Just below Half and flung down: Peek, not all the way to Hidden.
        assertEquals(SheetStop.Peek, pickStop(stops, 1050f, velocity = 3000f, velocityThreshold = 1000f))
        assertEquals(SheetStop.Full, pickStop(stops, 950f, velocity = -3000f, velocityThreshold = 1000f))
        // From rest at Half a fling up goes to Full, a fling down to Peek.
        assertEquals(SheetStop.Full, pickStop(stops, 1000f, velocity = -3000f, velocityThreshold = 1000f))
        assertEquals(SheetStop.Peek, pickStop(stops, 1000f, velocity = 3000f, velocityThreshold = 1000f))
    }

    @Test fun flingDownFromPeekDismissesAndPastTheEndsClamps() {
        assertEquals(SheetStop.Hidden, pickStop(stops, 1800f, velocity = 3000f, velocityThreshold = 1000f))
        assertEquals(SheetStop.Full, pickStop(stops, 200f, velocity = -3000f, velocityThreshold = 1000f))
    }
}
