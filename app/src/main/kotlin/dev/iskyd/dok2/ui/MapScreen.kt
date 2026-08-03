package dev.iskyd.dok2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.iskyd.dok2.data.repo.TrackRepository
import dev.iskyd.dok2.domain.model.TrackPoint
import dev.iskyd.dok2.recording.RecordingStateHolder
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The track map. The MapLibre GL surface exists only while this screen is composed: [MapView] is
 * created here, forwarded every activity lifecycle event, and destroyed in `onDispose` — the app's
 * number-one battery rule. It renders the open track's points as a polyline and camera-follows the
 * last fix; the location engine stays in [dev.iskyd.dok2.recording.RecordingService].
 */
@Composable
fun MapScreen(trackRepository: TrackRepository) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val openTrackId by RecordingStateHolder.openTrackId.collectAsState()
    val lastLatLng by RecordingStateHolder.lastLatLng.collectAsState()

    var points by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    LaunchedEffect(openTrackId) {
        points = openTrackId?.let { trackRepository.getPoints(it) } ?: emptyList()
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var mapRequested by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // The GL surface must never outlive the visible map screen.
            mapView.onDestroy()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) { _ ->
            val loadedMap = map
            when {
                loadedMap == null && !mapRequested -> {
                    mapRequested = true
                    mapView.getMapAsync { loaded ->
                        map = loaded
                        loaded.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                            style.addSource(
                                GeoJsonSource(
                                    TRACK_SOURCE_ID,
                                    FeatureCollection.fromFeatures(emptyList()),
                                )
                            )
                            style.addLayer(
                                LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID)
                                    .withProperties(lineColor(TRACK_COLOR), lineWidth(TRACK_WIDTH))
                            )
                            styleReady = true
                        }
                    }
                }
                loadedMap != null && styleReady -> {
                    syncMap(loadedMap, points, lastLatLng)
                }
            }
        }
        if (openTrackId == null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = "Start recording to see your track",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun syncMap(map: MapLibreMap, points: List<TrackPoint>, lastLatLng: LatLng?) {
    val source = map.getStyle()?.getSource(TRACK_SOURCE_ID) as? GeoJsonSource ?: return
    val coordinates = points.map { Point.fromLngLat(it.lonDeg, it.latDeg) }
    source.setGeoJson(
        FeatureCollection.fromFeatures(
            listOf(Feature.fromGeometry(LineString.fromLngLats(coordinates)))
        )
    )
    val target =
        lastLatLng ?: coordinates.lastOrNull()?.let { LatLng(it.latitude(), it.longitude()) }
    if (target != null) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, CAMERA_ZOOM))
    }
}

private const val STYLE_URL = "asset://style.json"
private const val TRACK_SOURCE_ID = "track-source"
private const val TRACK_LAYER_ID = "track-layer"
private const val TRACK_COLOR = "#2E7D32"
private const val TRACK_WIDTH = 4f
private const val CAMERA_ZOOM = 15.0
