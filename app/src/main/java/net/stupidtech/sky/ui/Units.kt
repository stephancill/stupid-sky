package net.stupidtech.sky.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Metric display: metres, km/h, m/s and km. Source data arrives in feet, knots, ft/min and nm. */
object Units {
    private fun fmt(n: Int) = String.format(Locale.getDefault(), "%,d", n)

    /** Feet → metres, rounded to 10 m (the data isn't more precise than that). */
    fun altitude(ft: Int): String = "${fmt(((ft * 0.3048) / 10).roundToInt() * 10)} m"

    fun speed(kt: Double): String = "${fmt((kt * 1.852).roundToInt())} km/h"

    /** ft/min → m/s with a climb/descent arrow. */
    fun verticalRate(fpm: Int): String {
        val ms = fpm * 0.00508
        val arrow = when { ms > 0.5 -> "▲ "; ms < -0.5 -> "▼ "; else -> "" }
        return arrow + String.format(Locale.getDefault(), "%.1f m/s", abs(ms))
    }

    fun distance(nm: Double): String {
        val km = nm * 1.852
        return if (km < 10) String.format(Locale.getDefault(), "%.1f km", km) else "${fmt(km.roundToInt())} km"
    }
}
