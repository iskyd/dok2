package dev.iskyd.dok2.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.iskyd.dok2.data.map.MapRegionImportResult
import dev.iskyd.dok2.data.map.MapRegionRepository
import dev.iskyd.dok2.data.prefs.AppSettings
import dev.iskyd.dok2.data.prefs.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The settings screen: privacy-zone, export-EXIF and elevation-bound preferences from
 * [SettingsRepository], the map-data section ([MapRegionRepository]), plus the battery-optimization
 * request and a link to the OEM background-killer guide (no INTERNET permission needed — the link
 * opens an external browser).
 */
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    mapRegionRepository: MapRegionRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settingsFlow.collectAsState(initial = AppSettings())
    var zoneRadiusM by remember { mutableFloatStateOf(settings.privacyZoneRadiusM) }

    LaunchedEffect(settings.privacyZoneRadiusM) { zoneRadiusM = settings.privacyZoneRadiusM }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        HorizontalDivider()

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Privacy zone", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Trim exported tracks within the zone radius of the start point",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.privacyZoneEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { settingsRepository.setPrivacyZoneEnabled(enabled) }
                },
            )
        }

        if (settings.privacyZoneEnabled) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "Radius: ${zoneRadiusM.toInt()} m",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = zoneRadiusM,
                    onValueChange = { zoneRadiusM = it },
                    onValueChangeFinished = {
                        scope.launch { settingsRepository.setPrivacyZoneRadiusM(zoneRadiusM) }
                    },
                    valueRange = PRIVACY_ZONE_RADIUS_MIN_M..PRIVACY_ZONE_RADIUS_MAX_M,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Strip photo EXIF", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Remove GPS data from photos when exporting",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.stripExifOnExport,
                onCheckedChange = { strip ->
                    scope.launch { settingsRepository.setStripExifOnExport(strip) }
                },
            )
        }

        ElevationBoundsSection(
            ascentMaxMps = settings.elevationAscentMaxMps,
            descentMaxMps = settings.elevationDescentMaxMps,
            settingsRepository = settingsRepository,
        )

        MapDataSection(
            activeMapFileName = settings.activeMapFileName,
            mapRegionRepository = mapRegionRepository,
        )

        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        Button(onClick = { requestIgnoreBatteryOptimizations(context) }) {
            Text("Battery optimization")
        }

        OutlinedButton(onClick = { openUrl(context, OEM_KILLER_URL) }) {
            Text("OEM background-killer instructions")
        }
    }
}

/**
 * The elevation-bound section of the settings screen: two sliders for the rate gate's ascent and
 * descent limits in m/s, persisted on release (the privacy-zone slider pattern). The gate clamps
 * each fix's altitude step to `bound × 3 s` before gain/loss is accumulated; values above ~1.7 m/s
 * ascent let single-fix spikes exceed the 5 m hysteresis and book phantom gain, so the UI warns
 * about the trade-off instead of hiding it.
 */
@Composable
private fun ElevationBoundsSection(
    ascentMaxMps: Float,
    descentMaxMps: Float,
    settingsRepository: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    var ascentMps by remember { mutableFloatStateOf(ascentMaxMps) }
    var descentMps by remember { mutableFloatStateOf(descentMaxMps) }

    LaunchedEffect(ascentMaxMps) { ascentMps = ascentMaxMps }
    LaunchedEffect(descentMaxMps) { descentMps = descentMaxMps }

    Column(Modifier.fillMaxWidth()) {
        Text("Elevation bounds", style = MaterialTheme.typography.titleMedium)
        Text(
            "Max vertical speed (m/s) treated as real; faster altitude steps are clamped " +
                "as sensor spikes before gain/loss is booked.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ascent: ${"%.1f".format(ascentMps)} m/s",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = ascentMps,
            onValueChange = { ascentMps = it },
            onValueChangeFinished = {
                scope.launch { settingsRepository.setElevationAscentMaxMps(ascentMps) }
            },
            valueRange = ELEVATION_BOUND_MIN_MPS..ELEVATION_BOUND_MAX_MPS,
            steps = ELEVATION_BOUND_STEPS,
        )
        Text(
            text = "Descent: ${"%.1f".format(descentMps)} m/s",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = descentMps,
            onValueChange = { descentMps = it },
            onValueChangeFinished = {
                scope.launch { settingsRepository.setElevationDescentMaxMps(descentMps) }
            },
            valueRange = ELEVATION_BOUND_MIN_MPS..ELEVATION_BOUND_MAX_MPS,
            steps = ELEVATION_BOUND_STEPS,
        )
        Text(
            "Above 1.7 m/s ascent, single-fix spikes can book phantom gain.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The map-data section of the settings screen: the active offline region (name + size, or a hint
 * when none is configured), a picker for a Tilemaker-built `.pmtiles` file, and an error surface
 * for rejected or failed imports. The picked file is copied into app-private storage by
 * [MapRegionRepository]; the copy runs off the main thread (inside the repository) so the UI shows
 * only a disabled "Copying…" button. No persistable URI permission is taken — the copy happens
 * in-callback and the URI is never reopened.
 */
@Composable
private fun MapDataSection(activeMapFileName: String?, mapRegionRepository: MapRegionRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var regionFileName by remember { mutableStateOf<String?>(null) }
    var sizeBytes by remember { mutableLongStateOf(0L) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    // Refresh the row whenever the stored region changes (the import sets the setting only after a
    // complete copy; remove clears it). activeFile doubles as the startup existence check, so a
    // setting pointing at a deleted file reads as "no region".
    LaunchedEffect(activeMapFileName) {
        val file = mapRegionRepository.activeFile()
        regionFileName = file?.name
        sizeBytes = file?.length() ?: 0L
    }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                errorMessage = "could not read the selected file"
                return@rememberLauncherForActivityResult
            }
            val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "region"
            importing = true
            scope.launch {
                try {
                    when (val result = mapRegionRepository.import(displayName, input)) {
                        is MapRegionImportResult.Imported -> errorMessage = null
                        is MapRegionImportResult.Rejected -> errorMessage = result.reason
                        is MapRegionImportResult.Failed -> errorMessage = result.error
                    }
                } finally {
                    input.close()
                    importing = false
                }
            }
        }

    Column(Modifier.fillMaxWidth()) {
        Text("Map data", style = MaterialTheme.typography.titleMedium)
        Text(
            "An offline basemap built with Tilemaker (.pmtiles), shown on the Map tab",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(regionFileName ?: "No map data yet", style = MaterialTheme.typography.bodyMedium)
        if (regionFileName != null) {
            Text(
                "${"%.1f".format(sizeBytes / 1_048_576.0)} MB",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !importing) {
                Text(if (importing) "Copying…" else "Choose region file")
            }
            if (regionFileName != null) {
                TextButton(onClick = { scope.launch { mapRegionRepository.remove() } }) {
                    Text("Remove")
                }
            }
        }
        if (errorMessage != null) {
            Text(
                errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Resolves a display name for [uri] from the content provider's [OpenableColumns.DISPLAY_NAME]
 * column, or null when the provider does not expose one (the picker accepts any mime type, so any
 * provider may answer).
 */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
    return null
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
    try {
        context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        Log.w(TAG, "device does not expose battery optimization settings", error)
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (error: ActivityNotFoundException) {
        Log.w(TAG, "no browser available to open $url", error)
    }
}

private const val TAG = "SettingsScreen"
private const val OEM_KILLER_URL = "https://dontkillmyapp.com"
private const val PRIVACY_ZONE_RADIUS_MIN_M = 100f
private const val PRIVACY_ZONE_RADIUS_MAX_M = 2000f
private const val ELEVATION_BOUND_MIN_MPS = 0.5f
private const val ELEVATION_BOUND_MAX_MPS = 3.0f
private const val ELEVATION_BOUND_STEPS = 24
