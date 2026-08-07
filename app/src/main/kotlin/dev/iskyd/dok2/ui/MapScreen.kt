package dev.iskyd.dok2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import dev.iskyd.dok2.data.map.MapRegionRepository
import dev.iskyd.dok2.data.prefs.AppSettings
import dev.iskyd.dok2.data.prefs.SettingsRepository
import dev.iskyd.dok2.data.repo.TrackRepository
import dev.iskyd.dok2.domain.model.TrackPoint
import dev.iskyd.dok2.recording.RecordingStateHolder
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
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
fun MapScreen(
    trackRepository: TrackRepository,
    mapRegionRepository: MapRegionRepository,
    settingsRepository: SettingsRepository,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val openTrackId by RecordingStateHolder.openTrackId.collectAsState()
    val lastLatLng by RecordingStateHolder.lastLatLng.collectAsState()
    val settings by settingsRepository.settingsFlow.collectAsState(initial = AppSettings())

    // Live points from the open track, re-keyed to the track id so a new session re-queries and
    // the DAO Flow re-emits as points are written (event-driven, no polling). Null id -> idle map.
    val points by
        remember(openTrackId) {
                openTrackId?.let { trackRepository.observePoints(it) }
                    ?: flowOf<List<TrackPoint>>(emptyList())
            }
            .collectAsState(initial = emptyList())

    // Skips the track setGeoJson rebuild on recompositions that only carry a new fix (lastLatLng).
    var lastPoints by remember { mutableStateOf<List<TrackPoint>?>(null) }

    // Follow-mode camera (plan D1-D3). didInitialJump is per map open: MapScreen is destroyed on
    // tab switch, so the state below resets with the composition.
    var following by remember { mutableStateOf(true) }
    var didInitialJump by remember { mutableStateOf(false) }

    // The active region file, re-resolved whenever the configured file name changes. [activeFile]
    // re-checks the file on disk, so a setting pointing at a deleted file reads as "no region".
    var regionFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(settings.activeMapFileName) { regionFile = mapRegionRepository.activeFile() }

    // The file name the region source was last (re)bound to; suppresses the redundant rebind
    // right after the style-ready callback adds the source.
    var appliedFileName by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(regionFile) {
        val loadedMap = map
        val style = loadedMap?.getStyle()
        if (!styleReady || style == null) return@LaunchedEffect
        val source = style.getSource(REGION_SOURCE_ID) as? VectorSource
        val file = regionFile
        when {
            file == null -> {
                if (source != null) {
                    removeBasemapLayers(style)
                    style.removeSource(REGION_SOURCE_ID)
                    appliedFileName = null
                }
            }
            source == null -> {
                style.addSource(VectorSource(REGION_SOURCE_ID, pmtilesUrl(file)))
                basemapLayers().forEach(style::addLayer)
                appliedFileName = file.name
            }
            appliedFileName != file.name -> {
                // VectorSource has no setUrl in MapLibre 12.3.1: rebind by re-adding the source
                // under the same id (the layers keep referencing the id, so they pick up the new
                // archive without being re-added).
                style.removeSource(REGION_SOURCE_ID)
                style.addSource(VectorSource(REGION_SOURCE_ID, pmtilesUrl(file)))
                appliedFileName = file.name
            }
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
                        loaded.addOnCameraMoveStartedListener(
                            OnCameraMoveStartedListener { reason ->
                                // A gesture ends follow mode; cancel any in-flight follow animation
                                // so
                                // the camera cannot re-center after the handoff (plan D2).
                                if (reason == OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                    following = false
                                    loaded.cancelTransitions()
                                }
                            }
                        )
                        loaded.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                            regionFile?.let { file ->
                                style.addSource(VectorSource(REGION_SOURCE_ID, pmtilesUrl(file)))
                                basemapLayers().forEach(style::addLayer)
                                appliedFileName = file.name
                            }
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
                            // Added after the track layer so the dot draws above the line.
                            style.addSource(
                                GeoJsonSource(
                                    POSITION_SOURCE_ID,
                                    FeatureCollection.fromFeatures(emptyList()),
                                )
                            )
                            style.addLayer(
                                CircleLayer(POSITION_LAYER_ID, POSITION_SOURCE_ID)
                                    .withProperties(
                                        circleColor(POSITION_COLOR),
                                        circleRadius(POSITION_RADIUS),
                                        circleStrokeColor(POSITION_STROKE_COLOR),
                                        circleStrokeWidth(POSITION_STROKE_WIDTH),
                                    )
                            )
                            styleReady = true
                        }
                    }
                }
                loadedMap != null && styleReady -> {
                    lastPoints = syncMap(loadedMap, points, lastPoints)
                    syncPositionDot(loadedMap, lastLatLng)
                    // Camera math keys solely off the last fix (plan D1-D3) — never the points
                    // list.
                    val target = lastLatLng
                    if (target != null) {
                        if (!didInitialJump) {
                            // First fix after open: jump (no fly) to the position at CAMERA_ZOOM.
                            loadedMap.setCameraPosition(
                                CameraPosition.Builder().target(target).zoom(CAMERA_ZOOM).build()
                            )
                            didInitialJump = true
                        } else if (following) {
                            // Center-only follow preserves the user's zoom.
                            loadedMap.animateCamera(
                                CameraUpdateFactory.newLatLng(target),
                                FOLLOW_ANIMATION_MS,
                            )
                        }
                    }
                }
            }
        }
        val hintText =
            when {
                openTrackId == null -> "Start recording to see your track"
                regionFile == null -> "No map data — add a region file in Settings"
                else -> null
            }
        if (hintText != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        // Recenter button (plan Todo 5): returns to position at CAMERA_ZOOM and re-enables follow.
        // Hidden when there is nowhere to return to — idle map or fresh session with no fix (D6).
        if (lastLatLng != null || points.isNotEmpty()) {
            Surface(
                onClick = {
                    // The user explicitly asked to return to position, so this bypasses the
                    // gesture-handoff and initial-jump guards. animateCamera reports
                    // REASON_API_ANIMATION, not REASON_API_GESTURE, so follow stays on.
                    following = true
                    val target = lastLatLng ?: lastPointOf(points)
                    if (target != null) {
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(target, CAMERA_ZOOM))
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = "◎",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

// Rebuilds the polyline only when [points] changed since the last applied list, returning the list
// now applied so the caller can remember it; otherwise every recomposition (each 3 s fix) would
// rebuild the source.
private fun syncMap(
    map: MapLibreMap,
    points: List<TrackPoint>,
    lastApplied: List<TrackPoint>?,
): List<TrackPoint>? {
    val source = map.getStyle()?.getSource(TRACK_SOURCE_ID) as? GeoJsonSource ?: return lastApplied
    if (points == lastApplied) return lastApplied
    val coordinates = points.map { Point.fromLngLat(it.lonDeg, it.latDeg) }
    source.setGeoJson(
        FeatureCollection.fromFeatures(
            listOf(Feature.fromGeometry(LineString.fromLngLats(coordinates)))
        )
    )
    return points
}

// Camera target for the recenter button when there is no live fix: the track's last stored point.
private fun lastPointOf(points: List<TrackPoint>): LatLng? =
    points.lastOrNull()?.let { LatLng(it.latDeg, it.lonDeg) }

private fun syncPositionDot(map: MapLibreMap, lastLatLng: LatLng?) {
    val source = map.getStyle()?.getSource(POSITION_SOURCE_ID) as? GeoJsonSource ?: return
    source.setGeoJson(
        if (lastLatLng != null) {
            FeatureCollection.fromFeatures(
                listOf(
                    Feature.fromGeometry(
                        Point.fromLngLat(lastLatLng.longitude, lastLatLng.latitude)
                    )
                )
            )
        } else {
            FeatureCollection.fromFeatures(emptyList())
        }
    )
}

private fun pmtilesUrl(file: File): String = "pmtiles://file://" + file.absolutePath

/**
 * The basemap layers for the seven Tilemaker source-layers, in draw order: fills below, then lines,
 * then circles. Water renders as both fill and line. The source-layer names are the contract with
 * the Tilemaker profile and must not be renamed.
 */
private fun basemapLayers(): List<Layer> =
    listOf(
        FillLayer(LANDUSE_LAYER_ID, REGION_SOURCE_ID)
            .withSourceLayer("landuse")
            .withProperties(fillColor(LANDUSE_FILL_COLOR), fillOpacity(LANDUSE_FILL_OPACITY)),
        FillLayer(WATER_FILL_LAYER_ID, REGION_SOURCE_ID)
            .withSourceLayer("water")
            .withProperties(fillColor(WATER_FILL_COLOR), fillOpacity(WATER_FILL_OPACITY)),
        LineLayer(WATER_LINE_LAYER_ID, REGION_SOURCE_ID)
            .withSourceLayer("water")
            .withProperties(lineColor(WATER_LINE_COLOR), lineWidth(WATER_LINE_WIDTH)),
        LineLayer(CONTOUR_LAYER_ID, REGION_SOURCE_ID)
            .withSourceLayer("contour")
            .withProperties(lineColor(CONTOUR_COLOR), lineWidth(CONTOUR_WIDTH)),
        LineLayer(PATH_LAYER_ID, REGION_SOURCE_ID)
            .withSourceLayer("path")
            .withProperties(lineColor(PATH_COLOR), lineWidth(PATH_WIDTH), lineDasharray(PATH_DASH)),
        LineLayer(TRACK_FEATURE_LAYER_ID, REGION_SOURCE_ID)
            .withSourceLayer("track")
            .withProperties(lineColor(TRACK_FEATURE_COLOR), lineWidth(TRACK_FEATURE_WIDTH)),
        CircleLayer(PEAK_LAYER_ID, REGION_SOURCE_ID)
            .withSourceLayer("peak")
            .withProperties(
                circleColor(PEAK_COLOR),
                circleRadius(PEAK_RADIUS),
                circleStrokeColor(PEAK_STROKE_COLOR),
                circleStrokeWidth(PEAK_STROKE_WIDTH),
            ),
        CircleLayer(HUT_LAYER_ID, REGION_SOURCE_ID)
            .withSourceLayer("hut")
            .withProperties(
                circleColor(HUT_COLOR),
                circleRadius(HUT_RADIUS),
                circleStrokeColor(HUT_STROKE_COLOR),
                circleStrokeWidth(HUT_STROKE_WIDTH),
            ),
    )

private fun removeBasemapLayers(style: Style) {
    BASEMAP_LAYER_IDS.forEach(style::removeLayer)
}

private const val STYLE_URL = "asset://style.json"
private const val TRACK_SOURCE_ID = "track-source"
private const val TRACK_LAYER_ID = "track-layer"
// Warm orange: the old green route vanished against the green landuse/paths/tracks in the region style.
private const val TRACK_COLOR = "#F4511E"
private const val TRACK_WIDTH = 4f
private const val CAMERA_ZOOM = 15.0
private const val FOLLOW_ANIMATION_MS = 200
private const val POSITION_SOURCE_ID = "position-source"
private const val POSITION_LAYER_ID = "position-layer"
private const val POSITION_COLOR = "#1565C0"
private const val POSITION_RADIUS = 6.5f
private const val POSITION_STROKE_COLOR = "#FFFFFF"
private const val POSITION_STROKE_WIDTH = 2f

private const val REGION_SOURCE_ID = "region-source"
private const val LANDUSE_LAYER_ID = "landuse-fill"
private const val WATER_FILL_LAYER_ID = "water-fill"
private const val WATER_LINE_LAYER_ID = "water-line"
private const val CONTOUR_LAYER_ID = "contour-line"
private const val PATH_LAYER_ID = "path-line"
private const val TRACK_FEATURE_LAYER_ID = "track-line"
private const val PEAK_LAYER_ID = "peak-circle"
private const val HUT_LAYER_ID = "hut-circle"
private val BASEMAP_LAYER_IDS =
    listOf(
        LANDUSE_LAYER_ID,
        WATER_FILL_LAYER_ID,
        WATER_LINE_LAYER_ID,
        CONTOUR_LAYER_ID,
        PATH_LAYER_ID,
        TRACK_FEATURE_LAYER_ID,
        PEAK_LAYER_ID,
        HUT_LAYER_ID,
    )

private const val LANDUSE_FILL_COLOR = "#D6E4C0"
private const val LANDUSE_FILL_OPACITY = 0.7f
private const val WATER_FILL_COLOR = "#A8C8E0"
private const val WATER_FILL_OPACITY = 0.8f
private const val WATER_LINE_COLOR = "#5B9BD5"
private const val WATER_LINE_WIDTH = 1f
private const val CONTOUR_COLOR = "#B08968"
private const val CONTOUR_WIDTH = 0.8f
private const val PATH_COLOR = "#7CB342"
private const val PATH_WIDTH = 2f

// PropertyFactory.lineDasharray takes a boxed Float[] (there is no float[] overload), so this must
// be an Array<Float>, not FloatArray.
private val PATH_DASH = arrayOf(2f, 1.5f)
private const val TRACK_FEATURE_COLOR = "#558B2F"
private const val TRACK_FEATURE_WIDTH = 2.5f
private const val PEAK_COLOR = "#C62828"
private const val PEAK_RADIUS = 4f
private const val PEAK_STROKE_COLOR = "#FFFFFF"
private const val PEAK_STROKE_WIDTH = 1f
private const val HUT_COLOR = "#6D4C41"
private const val HUT_RADIUS = 3.5f
private const val HUT_STROKE_COLOR = "#FFFFFF"
private const val HUT_STROKE_WIDTH = 1f
