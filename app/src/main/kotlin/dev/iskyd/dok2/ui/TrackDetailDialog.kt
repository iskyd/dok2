package dev.iskyd.dok2.ui

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.iskyd.dok2.data.repo.TrackRepository
import dev.iskyd.dok2.domain.model.ElevationExtremes
import dev.iskyd.dok2.domain.model.Track
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only summary of a finished track: distance, times, pace and elevation figures. Elevation
 * extremes are loaded from the repository when the dialog opens (DEM-preferred, GNSS fallback); the
 * gain/loss figures come straight from the stored [Track].
 */
@Composable
fun TrackDetailDialog(track: Track, trackRepository: TrackRepository, onDismiss: () -> Unit) {
    val extremes by
        produceState<ElevationExtremes?>(null, track.id) {
            value = trackRepository.getElevationExtremes(track.id)
        }

    val gainM = track.gainDemM ?: track.gainBaroM
    val lossM = track.lossDemM ?: track.lossBaroM

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.name ?: formatDate(track.startedAtMs)) },
        text = {
            Column {
                Text(
                    formatDate(track.startedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                track.notes?.let { notes ->
                    Spacer(Modifier.height(8.dp))
                    Text(notes, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                StatsRow(
                    "Distance" to formatDistance(track.distanceM),
                    "Duration" to formatDuration(track.elapsedTimeS),
                )
                Spacer(Modifier.height(8.dp))
                StatsRow(
                    "Moving time" to formatDuration(track.movingTimeS),
                    "Pace" to formatPace(track.movingTimeS, track.distanceM),
                )
                Spacer(Modifier.height(8.dp))
                StatsRow(
                    "Elevation gain" to (gainM?.let { "▲ ${formatDistance(it)}" } ?: "—"),
                    "Elevation loss" to (lossM?.let { "▼ ${formatDistance(it)}" } ?: "—"),
                )
                Spacer(Modifier.height(8.dp))
                StatsRow(
                    "Max elevation" to (extremes?.maxM?.let { "${it.toInt()} m" } ?: "—"),
                    "Min elevation" to (extremes?.minM?.let { "${it.toInt()} m" } ?: "—"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    sharing = true
                    scope.launch {
                        try {
                            val points = trackRepository.getPoints(track.id)
                            val uri =
                                withContext(Dispatchers.Default) {
                                    val bitmap =
                                        StatsImageRenderer.render(
                                            points,
                                            track.distanceM,
                                            gainM,
                                            track.elapsedTimeS,
                                        )
                                    val dir = File(context.cacheDir, "shared")
                                    dir.mkdirs()
                                    dir.listFiles { _, name -> name.startsWith("dok2-stats-") }
                                        ?.forEach { it.delete() }
                                    val file =
                                        File(dir, "dok2-stats-${System.currentTimeMillis()}.png")
                                    file.outputStream().use {
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                    bitmap.recycle()
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                }
                            val intent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            context.startActivity(Intent.createChooser(intent, "Share stats image"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not share the image", Toast.LENGTH_SHORT)
                                .show()
                        } finally {
                            sharing = false
                        }
                    }
                },
                enabled = !sharing,
            ) {
                Text(if (sharing) "Generating…" else "Share image")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun StatsRow(first: Pair<String, String>, second: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatCell(first.first, first.second)
        StatCell(second.first, second.second)
    }
}

@Composable
private fun RowScope.StatCell(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatDistance(distanceM: Double): String =
    if (distanceM >= 1000) {
        String.format(Locale.ROOT, "%.2f km", distanceM / 1000)
    } else {
        String.format(Locale.ROOT, "%.0f m", distanceM)
    }

private fun formatDuration(elapsedTimeS: Long): String {
    val hours = elapsedTimeS / 3600
    val minutes = (elapsedTimeS % 3600) / 60
    val seconds = elapsedTimeS % 60
    return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
}

private fun formatDate(tMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(tMs))

/** Pace in minutes per kilometre from moving time; "—" for tracks too short to be meaningful. */
private fun formatPace(movingTimeS: Long, distanceM: Double): String {
    if (distanceM < 100.0) return "—"
    val secondsPerKm = movingTimeS / (distanceM / 1000.0)
    val minutes = (secondsPerKm / 60).toInt()
    val seconds = (secondsPerKm % 60).toInt()
    return String.format(Locale.ROOT, "%d:%02d /km", minutes, seconds)
}
