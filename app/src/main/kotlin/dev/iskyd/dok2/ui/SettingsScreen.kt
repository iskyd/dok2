package dev.iskyd.dok2.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.iskyd.dok2.data.prefs.AppSettings
import dev.iskyd.dok2.data.prefs.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The settings screen: privacy-zone and export-EXIF preferences from [SettingsRepository], plus the
 * battery-optimization request and a link to the OEM background-killer guide (no INTERNET
 * permission needed — the link opens an external browser).
 */
@Composable
fun SettingsScreen(settingsRepository: SettingsRepository) {
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
