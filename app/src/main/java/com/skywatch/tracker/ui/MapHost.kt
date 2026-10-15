package com.skywatch.tracker.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.skywatch.tracker.map.MapLayers
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

@Composable
fun MapHost(
    state: UiState,
    onViewport: (Viewport) -> Unit,
    onAircraftTap: (String?) -> Unit,
    onMetarTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var layers by remember { mutableStateOf<MapLayers?>(null) }
    val currentOnViewport by rememberUpdatedState(onViewport)
    val currentOnAircraftTap by rememberUpdatedState(onAircraftTap)
    val currentOnMetarTap by rememberUpdatedState(onMetarTap)

    var style by remember { mutableStateOf<Style?>(null) }
    val prefs = remember { context.getSharedPreferences("camera", Context.MODE_PRIVATE) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                // Start where the user last looked; the device location takes over once known.
                m.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(prefs.getFloat("lat", 51.5f).toDouble(), prefs.getFloat("lon", 0f).toDouble()))
                    .zoom(prefs.getFloat("zoom", 7f).toDouble())
                    .build()
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.setStyle(STYLE_URL) { s ->
                    layers = MapLayers(s)
                    style = s
                    map = m
                    currentOnViewport(m.viewport())
                }
                m.addOnCameraIdleListener {
                    val c = m.cameraPosition
                    c.target?.let { t ->
                        prefs.edit()
                            .putFloat("lat", t.latitude.toFloat())
                            .putFloat("lon", t.longitude.toFloat())
                            .putFloat("zoom", c.zoom.toFloat())
                            .apply()
                    }
                    currentOnViewport(m.viewport())
                }
                m.addOnMapClickListener { latLng ->
                    val p = m.projection.toScreenLocation(latLng)
                    val r = 24f * resources.displayMetrics.density
                    val box = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
                    val ac = m.queryRenderedFeatures(box, MapLayers.LAYER_AIRCRAFT).firstOrNull()
                    val metar = if (ac == null) m.queryRenderedFeatures(box, MapLayers.LAYER_METAR).firstOrNull() else null
                    when {
                        ac != null -> currentOnAircraftTap(ac.getStringProperty("hex"))
                        metar != null -> currentOnMetarTap(metar.getStringProperty("id"))
                        else -> currentOnAircraftTap(null)
                    }
                    true
                }
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)

    val l = layers
    if (l != null) {
        LaunchedEffect(l, state.aircraft, state.selected?.hex) { l.setAircraft(state.aircraft, state.selected?.hex) }
        LaunchedEffect(l, state.selectedTrail) { l.setTrail(state.selectedTrail) }
        LaunchedEffect(l, state.metars, state.showMetars) { l.setMetars(if (state.showMetars) state.metars else emptyList()) }
        LaunchedEffect(l, state.showRadar, state.radarTiles) { l.setRadar(if (state.showRadar) state.radarTiles else null) }
    }
    LaunchedEffect(map, state.camera?.id) {
        val m = map ?: return@LaunchedEffect
        val c = state.camera ?: return@LaunchedEffect
        val zoom = c.zoom ?: maxOf(m.cameraPosition.zoom, 8.0)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.lat, c.lon), zoom), 1200)
    }
    // Blue "you are here" dot.
    LaunchedEffect(map, style, state.locationGranted) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (state.locationGranted) m.showUserLocation(context, s)
    }
}

@SuppressLint("MissingPermission")
private fun MapLibreMap.showUserLocation(context: Context, style: Style) {
    runCatching {
        locationComponent.apply {
            if (!isLocationComponentActivated) {
                activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style)
                        .useDefaultLocationEngine(true)
                        .build(),
                )
            }
            isLocationComponentEnabled = true
            cameraMode = CameraMode.NONE
            renderMode = RenderMode.NORMAL
        }
    }
}

private fun MapLibreMap.viewport(): Viewport {
    val b = projection.visibleRegion.latLngBounds
    val c = cameraPosition.target ?: b.center
    val radius = maxOf(
        distanceNm(c.latitude, c.longitude, b.latitudeNorth, b.longitudeEast),
        distanceNm(c.latitude, c.longitude, b.latitudeSouth, b.longitudeWest),
    )
    return Viewport(
        lat = c.latitude, lon = c.longitude,
        radiusNm = radius.roundToInt().coerceIn(5, 250),
        south = b.latitudeSouth, west = b.longitudeWest, north = b.latitudeNorth, east = b.longitudeEast,
    )
}

private fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 3440.065 // earth radius, nm
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
