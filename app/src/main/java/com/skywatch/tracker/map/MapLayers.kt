package com.skywatch.tracker.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.skywatch.tracker.data.Aircraft
import com.skywatch.tracker.data.Airport
import com.skywatch.tracker.data.Metar
import com.skywatch.tracker.data.RouteInfo
import com.skywatch.tracker.data.LatLon
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconColor
import org.maplibre.android.style.layers.PropertyFactory.iconHaloBlur
import org.maplibre.android.style.layers.PropertyFactory.iconHaloColor
import org.maplibre.android.style.layers.PropertyFactory.iconHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Owns every SkyWatch source/layer on top of the base map style. */
class MapLayers(private val style: Style) {

    private val aircraftSource = GeoJsonSource(SRC_AIRCRAFT)
    private val trailSource = GeoJsonSource(SRC_TRAIL)
    private val metarSource = GeoJsonSource(SRC_METAR)
    private val routeSource = GeoJsonSource(SRC_ROUTE)
    private val airportSource = GeoJsonSource(SRC_AIRPORTS)
    private var radarUrl: String? = null

    init {
        style.addImage(ICON_PLANE, planeBitmap(), true)
        style.addImage(ICON_HELI, heliBitmap(), true)

        style.addSource(trailSource)
        style.addSource(routeSource)
        style.addSource(airportSource)
        style.addSource(metarSource)
        style.addSource(aircraftSource)

        style.addLayer(
            LineLayer(LAYER_TRAIL, SRC_TRAIL).withProperties(
                lineColor(altitudeColor()),
                lineWidth(3f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )

        // Remaining route: dashed great circle from the aircraft to its destination.
        style.addLayerBelow(
            LineLayer(LAYER_ROUTE, SRC_ROUTE).withProperties(
                lineColor("#FFFFFF"),
                lineOpacity(0.85f),
                lineWidth(2f),
                lineDasharray(arrayOf(2f, 2f)),
            ),
            LAYER_TRAIL,
        )
        style.addLayer(
            CircleLayer(LAYER_AIRPORTS, SRC_AIRPORTS).withProperties(
                circleRadius(5f),
                circleColor("#FFFFFF"),
                circleStrokeColor("#212121"),
                circleStrokeWidth(2f),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_AIRPORT_LABELS, SRC_AIRPORTS).withProperties(
                textField(get("code")),
                textFont(FONT),
                textSize(13f),
                textOffset(arrayOf(0f, -1.2f)),
                textAnchor(Property.TEXT_ANCHOR_BOTTOM),
                textColor("#FFFFFF"),
                textHaloColor("#CC000000"),
                textHaloWidth(1.5f),
                textAllowOverlap(true),
            ),
        )

        style.addLayer(
            CircleLayer(LAYER_METAR, SRC_METAR).withProperties(
                circleRadius(7f),
                circleColor(
                    Expression.match(
                        get("cat"), literal("#9E9E9E"),
                        Expression.stop("VFR", "#2E7D32"),
                        Expression.stop("MVFR", "#1565C0"),
                        Expression.stop("IFR", "#C62828"),
                        Expression.stop("LIFR", "#AD1457"),
                    ),
                ),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(1.5f),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_METAR_LABEL, SRC_METAR).withProperties(
                textField(get("id")),
                textFont(FONT),
                textSize(11f),
                textOffset(arrayOf(0f, 1.3f)),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textColor("#212121"),
                textHaloColor("#FFFFFF"),
                textHaloWidth(1.5f),
                textOptional(true),
            ).also { it.minZoom = 6f },
        )

        style.addLayer(
            SymbolLayer(LAYER_AIRCRAFT, SRC_AIRCRAFT).withProperties(
                iconImage(get("icon")),
                iconRotate(get("track")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconSize(
                    Expression.switchCase(
                        eq(get("sel"), literal(true)), literal(0.75f),
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(3, 0.35f), Expression.stop(10, 0.6f),
                        ),
                    ),
                ),
                iconColor(
                    Expression.switchCase(
                        eq(get("emerg"), literal(true)), Expression.color(Color.parseColor("#FF1744")),
                        eq(get("mil"), literal(true)), Expression.color(Color.parseColor("#76FF03")),
                        eq(get("gnd"), literal(true)), Expression.color(Color.parseColor("#9E9E9E")),
                        Expression.color(Color.parseColor("#FFC400")),
                    ),
                ),
                iconHaloColor(
                    Expression.switchCase(
                        eq(get("sel"), literal(true)), Expression.color(Color.parseColor("#00E5FF")),
                        Expression.color(Color.parseColor("#B3000000")),
                    ),
                ),
                iconHaloWidth(Expression.switchCase(eq(get("sel"), literal(true)), literal(3f), literal(1.2f))),
                iconHaloBlur(0.5f),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_AIRCRAFT_LABEL, SRC_AIRCRAFT).withProperties(
                textField(get("label")),
                textFont(FONT),
                textSize(11f),
                textOffset(arrayOf(0f, 1.6f)),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textColor("#FFFFFF"),
                textHaloColor("#CC000000"),
                textHaloWidth(1.5f),
                textAllowOverlap(false),
                textOptional(true),
            ).also { it.minZoom = 7.5f },
        )
    }

    fun setAircraft(list: List<Aircraft>, selectedHex: String?, routes: Map<String, RouteInfo>) {
        val features = list.map { a ->
            Feature.fromGeometry(Point.fromLngLat(a.lon!!, a.lat!!)).apply {
                addStringProperty("hex", a.hex)
                val r = a.callsign?.let { routes[it] }
                val routeText = if (r?.hasRoute == true) "\n${r.origin!!.code}→${r.destination!!.code}" else ""
                addStringProperty("label", a.label + routeText)
                addStringProperty("icon", if (a.category == "A7") ICON_HELI else ICON_PLANE)
                addNumberProperty("track", a.track ?: a.trueHeading ?: 0.0)
                addBooleanProperty("mil", a.isMilitary)
                addBooleanProperty("emerg", a.isEmergency)
                addBooleanProperty("gnd", a.onGround)
                addBooleanProperty("sel", a.hex == selectedHex)
            }
        }
        aircraftSource.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Trail drawn as short segments so each can be coloured by altitude. */
    fun setTrail(points: List<LatLon>) {
        val features = points.zipWithNext().map { (a, b) ->
            Feature.fromGeometry(
                LineString.fromLngLats(listOf(Point.fromLngLat(a.lon, a.lat), Point.fromLngLat(b.lon, b.lat))),
            ).apply { addNumberProperty("alt", b.alt ?: 0) }
        }
        trailSource.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Dashed line to the destination plus origin/destination markers; nulls clear it. */
    fun setRoute(position: LatLon?, origin: Airport?, destination: Airport?) {
        val dLat = destination?.latitude
        val dLon = destination?.longitude
        val line = if (position != null && dLat != null && dLon != null) {
            listOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(greatCircle(position.lat, position.lon, dLat, dLon)),
                ),
            )
        } else emptyList()
        routeSource.setGeoJson(FeatureCollection.fromFeatures(line))

        val airports = listOfNotNull(origin, destination).mapNotNull { a ->
            val la = a.latitude ?: return@mapNotNull null
            val lo = a.longitude ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(lo, la)).apply { addStringProperty("code", a.code) }
        }
        airportSource.setGeoJson(FeatureCollection.fromFeatures(airports))
    }

    fun setMetars(list: List<Metar>) {
        val features = list.map { m ->
            Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
                addStringProperty("id", m.icaoId)
                addStringProperty("cat", m.fltCat ?: "")
            }
        }
        metarSource.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun setRadar(tileUrl: String?) {
        if (tileUrl == radarUrl) return
        style.getLayer(LAYER_RADAR)?.let { style.removeLayer(it) }
        style.getSource(SRC_RADAR)?.let { style.removeSource(it) }
        radarUrl = tileUrl
        if (tileUrl == null) return
        // RainViewer's free tiles stop at zoom 7; MapLibre over-zooms beyond that.
        val tiles = TileSet("2.2.0", tileUrl).apply { maxZoom = 7f; attribution = "RainViewer" }
        style.addSource(RasterSource(SRC_RADAR, tiles, 256))
        style.addLayerBelow(RasterLayer(LAYER_RADAR, SRC_RADAR).withProperties(rasterOpacity(0.6f)), LAYER_TRAIL)
    }

    /** Points along the great circle, with longitudes kept continuous across ±180°. */
    private fun greatCircle(lat1: Double, lon1: Double, lat2: Double, lon2: Double, n: Int = 64): List<Point> {
        val φ1 = Math.toRadians(lat1); val λ1 = Math.toRadians(lon1)
        val φ2 = Math.toRadians(lat2); val λ2 = Math.toRadians(lon2)
        val d = 2 * asin(sqrt(sin((φ2 - φ1) / 2).pow(2) + cos(φ1) * cos(φ2) * sin((λ2 - λ1) / 2).pow(2)))
        if (d < 1e-9) return listOf(Point.fromLngLat(lon1, lat1), Point.fromLngLat(lon2, lat2))
        var prevLon = lon1
        return (0..n).map { i ->
            val f = i.toDouble() / n
            val a = sin((1 - f) * d) / sin(d)
            val b = sin(f * d) / sin(d)
            val x = a * cos(φ1) * cos(λ1) + b * cos(φ2) * cos(λ2)
            val y = a * cos(φ1) * sin(λ1) + b * cos(φ2) * sin(λ2)
            val z = a * sin(φ1) + b * sin(φ2)
            val lat = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
            var lon = Math.toDegrees(atan2(y, x))
            while (lon - prevLon > 180) lon -= 360
            while (lon - prevLon < -180) lon += 360
            prevLon = lon
            Point.fromLngLat(lon, lat)
        }
    }

    private fun altitudeColor(): Expression = Expression.interpolate(
        Expression.linear(), get("alt"),
        Expression.stop(0, Expression.color(Color.parseColor("#FF6D00"))),
        Expression.stop(5000, Expression.color(Color.parseColor("#FFD600"))),
        Expression.stop(15000, Expression.color(Color.parseColor("#64DD17"))),
        Expression.stop(25000, Expression.color(Color.parseColor("#00B8D4"))),
        Expression.stop(35000, Expression.color(Color.parseColor("#2962FF"))),
        Expression.stop(45000, Expression.color(Color.parseColor("#D500F9"))),
    )

    companion object {
        const val LAYER_AIRCRAFT = "sw-aircraft"
        const val LAYER_METAR = "sw-metar"
        private const val LAYER_AIRCRAFT_LABEL = "sw-aircraft-label"
        private const val LAYER_METAR_LABEL = "sw-metar-label"
        private const val LAYER_TRAIL = "sw-trail"
        private const val LAYER_RADAR = "sw-radar"
        private const val LAYER_ROUTE = "sw-route"
        private const val LAYER_AIRPORTS = "sw-airports"
        private const val LAYER_AIRPORT_LABELS = "sw-airport-labels"
        private const val SRC_ROUTE = "sw-route"
        private const val SRC_AIRPORTS = "sw-airports"
        private const val SRC_AIRCRAFT = "sw-aircraft"
        private const val SRC_TRAIL = "sw-trail"
        private const val SRC_METAR = "sw-metar"
        private const val SRC_RADAR = "sw-radar"
        private const val ICON_PLANE = "sw-plane"
        private const val ICON_HELI = "sw-heli"
        private val FONT = arrayOf("Noto Sans Regular")

        /** White, nose-up airliner silhouette used as an SDF so it can be tinted. */
        private fun planeBitmap(): Bitmap {
            val s = 64f
            val bmp = Bitmap.createBitmap(s.toInt(), s.toInt(), Bitmap.Config.ARGB_8888)
            val p = Path().apply {
                moveTo(32f, 2f)          // nose
                cubicTo(35f, 4f, 36f, 10f, 36f, 18f)
                lineTo(36f, 24f)
                lineTo(62f, 38f)         // right wing
                lineTo(62f, 43f)
                lineTo(36f, 36f)
                lineTo(35f, 50f)
                lineTo(44f, 57f)         // right tailplane
                lineTo(44f, 61f)
                lineTo(32f, 58f)
                lineTo(20f, 61f)
                lineTo(20f, 57f)         // left tailplane
                lineTo(29f, 50f)
                lineTo(28f, 36f)
                lineTo(2f, 43f)
                lineTo(2f, 38f)          // left wing
                lineTo(28f, 24f)
                lineTo(28f, 18f)
                cubicTo(28f, 10f, 29f, 4f, 32f, 2f)
                close()
            }
            Canvas(bmp).drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            return bmp
        }

        private fun heliBitmap(): Bitmap {
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val stroke = Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
            c.drawOval(24f, 14f, 40f, 40f, fill)           // cabin
            c.drawRect(30f, 38f, 34f, 58f, fill)           // tail boom
            c.drawRect(24f, 55f, 40f, 59f, fill)           // tail rotor
            c.drawLine(8f, 8f, 56f, 48f, stroke)           // main rotor
            c.drawLine(56f, 8f, 8f, 48f, stroke)
            return bmp
        }
    }
}
