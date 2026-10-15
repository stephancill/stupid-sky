package net.stupidtech.sky.data

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** One area query: a circle the networks accept (radius at most [Provider.MAX_RADIUS_NM]). */
data class Circle(val lat: Double, val lon: Double, val radiusNm: Int)

/** Circles covering a view. [cropped] means the view was too big and only its centre is covered. */
data class Tiling(val circles: List<Circle>, val cropped: Boolean)

/**
 * Covers a bounding box with area queries. Both networks cap a query at 250 nm, so a zoomed-out view
 * (e.g. all of South Africa, ~1,600 km across) would otherwise only load a circle around its centre.
 */
object Tiles {
    /**
     * At most this many circles per load; each is one request per network, spaced by the throttle (~1 s).
     * A portrait phone showing all of South Africa needs ~21. Wide views are loaded only on request.
     */
    const val MAX = 24

    // A square of side r·√2 fits inside a circle of radius r; a bit less leaves overlap at the corners.
    private const val CELL_NM = Provider.MAX_RADIUS_NM * 1.38

    fun cover(box: BoundingBox, maxTiles: Int = MAX): Tiling {
        var b = box
        var cropped = false
        while (true) {
            val circles = grid(b)
            if (circles.size <= maxTiles) {
                // Centre first, so what the user is looking at fills in before the edges.
                val cLat = (b.south + b.north) / 2
                val cLon = (b.west + b.east) / 2
                return Tiling(circles.sortedBy { (it.lat - cLat).let { d -> d * d } + (it.lon - cLon).let { d -> d * d } }, cropped)
            }
            // Shrink around the centre in proportion to the excess and try again.
            val f = sqrt(maxTiles.toDouble() / circles.size) * 0.97
            val cLat = (b.south + b.north) / 2
            val cLon = (b.west + b.east) / 2
            val hLat = (b.north - b.south) / 2 * f
            val hLon = (b.east - b.west) / 2 * f
            b = BoundingBox(cLat - hLat, cLon - hLon, cLat + hLat, cLon + hLon)
            cropped = true
        }
    }

    private fun grid(b: BoundingBox): List<Circle> {
        val heightNm = (b.north - b.south) * 60
        val rows = max(1, ceil(heightNm / CELL_NM).toInt())
        val rowDeg = (b.north - b.south) / rows
        return (0 until rows).flatMap { r ->
            val lo = b.south + rowDeg * r
            val hi = lo + rowDeg
            val lat = lo + rowDeg / 2
            // Size columns at the row edge nearest the equator, where a degree of longitude is widest.
            val widestLat = if (lo <= 0 && hi >= 0) 0.0 else minOf(abs(lo), abs(hi))
            val widthNm = (b.east - b.west) * 60 * cos(Math.toRadians(widestLat))
            val cols = max(1, ceil(widthNm / CELL_NM).toInt())
            val colDeg = (b.east - b.west) / cols
            // Radius reaching the cell corners, so a single small cell keeps a small query.
            val halfH = rowDeg * 60 / 2
            val halfW = colDeg * 60 * cos(Math.toRadians(widestLat)) / 2
            val radius = ceil(sqrt(halfH * halfH + halfW * halfW)).toInt().coerceIn(1, Provider.MAX_RADIUS_NM)
            (0 until cols).map { c -> Circle(lat, b.west + colDeg * (c + 0.5), radius) }
        }
    }
}
