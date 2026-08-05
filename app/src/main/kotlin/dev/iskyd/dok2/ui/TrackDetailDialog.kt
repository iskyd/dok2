package dev.iskyd.dok2.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
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
import java.io.IOException
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
    // One shared state disables both buttons while the (shared, expensive) render runs.
    var activeAction by remember { mutableStateOf<ImageAction?>(null) }

    // Pre-Q (API 26-28) has no MediaStore.Downloads; fall back to the Storage Access Framework's
    // system picker so no storage permission is ever requested.
    val createDocumentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri
            ->
            if (uri != null) {
                scope.launch {
                    activeAction = ImageAction.Download
                    try {
                        val file = renderStatsImage(context, trackRepository, track, gainM)
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                file.inputStream().use { it.copyTo(out) }
                            } ?: throw IOException("Could not open $uri")
                        }
                        Toast.makeText(context, "Image saved", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not save the image", Toast.LENGTH_SHORT)
                            .show()
                    } finally {
                        activeAction = null
                    }
                }
            }
        }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            activeAction = ImageAction.Download
                            scope.launch {
                                try {
                                    val file =
                                        renderStatsImage(context, trackRepository, track, gainM)
                                    saveToDownloads(context, file)
                                    Toast.makeText(
                                            context,
                                            "Saved to Downloads",
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                            context,
                                            "Could not save the image",
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                } finally {
                                    activeAction = null
                                }
                            }
                        } else {
                            createDocumentLauncher.launch(
                                "dok2-stats-${System.currentTimeMillis()}.png"
                            )
                        }
                    },
                    enabled = activeAction == null,
                ) {
                    Text(
                        if (activeAction == ImageAction.Download) "Generating…"
                        else "Download image"
                    )
                }
                TextButton(
                    onClick = {
                        activeAction = ImageAction.Share
                        scope.launch {
                            try {
                                val file = renderStatsImage(context, trackRepository, track, gainM)
                                val uri =
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                val intent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                context.startActivity(
                                    Intent.createChooser(intent, "Share stats image")
                                )
                            } catch (e: Exception) {
                                Toast.makeText(
                                        context,
                                        "Could not share the image",
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            } finally {
                                activeAction = null
                            }
                        }
                    },
                    enabled = activeAction == null,
                ) {
                    Text(if (activeAction == ImageAction.Share) "Generating…" else "Share image")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The image action currently running; both buttons are disabled while one is. */
private enum class ImageAction {
    Share,
    Download,
}

/** Renders the stats PNG into a fresh cache file and returns it. */
private suspend fun renderStatsImage(
    context: Context,
    trackRepository: TrackRepository,
    track: Track,
    gainM: Double?,
): File {
    val points = trackRepository.getPoints(track.id)
    return withContext(Dispatchers.Default) {
        val bitmap = StatsImageRenderer.render(points, track.distanceM, gainM, track.elapsedTimeS)
        val dir = File(context.cacheDir, "shared")
        dir.mkdirs()
        dir.listFiles { _, name -> name.startsWith("dok2-stats-") }?.forEach { it.delete() }
        val file = File(dir, "dok2-stats-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        file
    }
}

/**
 * Copies [file] into the public Downloads collection via MediaStore. API 29+ only: scoped storage
 * allows writing there without any permission. Returns the new content URI.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun saveToDownloads(context: Context, file: File): Uri =
    withContext(Dispatchers.IO) {
        val values =
            ContentValues().apply {
                put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    "dok2-stats-${System.currentTimeMillis()}.png",
                )
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        val uri =
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Could not open output stream for $uri")
        uri
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
