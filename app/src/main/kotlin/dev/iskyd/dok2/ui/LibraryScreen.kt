package dev.iskyd.dok2.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.iskyd.dok2.data.repo.TrackRepository
import dev.iskyd.dok2.domain.model.RecordingState
import dev.iskyd.dok2.domain.model.Track
import dev.iskyd.dok2.domain.model.TrackSummary
import dev.iskyd.dok2.recording.RecordingService
import dev.iskyd.dok2.recording.RecordingStateHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The track list. Renders summary cards only — it never instantiates a map view; the thumbnail box
 * stays a placeholder while `thumbnail_path` remains null (AGENTS.md performance rule). If a track
 * was left open (process kill, reboot), a banner offers to resume recording into it or finalise it
 * (mitigation 5).
 */
@Composable
fun LibraryScreen(trackRepository: TrackRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val summaries by trackRepository.observeTrackSummaries().collectAsState(initial = emptyList())
    val recordingState by RecordingStateHolder.state.collectAsState()
    val liveTrackId by RecordingStateHolder.openTrackId.collectAsState()
    var openTrack by remember { mutableStateOf<Track?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var trackToDelete by remember { mutableStateOf<TrackSummary?>(null) }

    LaunchedEffect(refreshKey, trackRepository) { openTrack = trackRepository.getOpenTrack() }

    Column(Modifier.fillMaxSize()) {
        // The banner is crash/reboot recovery only: while the service is recording, the open track
        // is live and must not be offered for finalisation.
        if (recordingState == RecordingState.Idle) {
            openTrack?.let { track ->
                OpenTrackBanner(
                    track = track,
                    onResume = {
                        resumeOpenTrack(context)
                        refreshKey++
                    },
                    onFinalise = {
                        scope.launch {
                            finaliseOpenTrack(trackRepository, track)
                            refreshKey++
                        }
                    },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(summaries, key = { it.id }) { summary ->
                // The track being recorded into must not be deletable.
                TrackCard(
                    summary = summary,
                    canDelete = summary.id != liveTrackId,
                    onDelete = { trackToDelete = summary },
                )
            }
        }
    }

    trackToDelete?.let { summary ->
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Delete track?") },
            text = {
                Text(
                    "${summary.name ?: formatDate(summary.startedAtMs)} — " +
                        "This removes the track and all its points. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        trackToDelete = null
                        scope.launch {
                            trackRepository.deleteTrack(summary.id)
                            // Reload getOpenTrack() only after the delete commits, so a deleted
                            // open track cannot leave a stale banner behind.
                            refreshKey++
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { trackToDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OpenTrackBanner(track: Track, onResume: () -> Unit, onFinalise: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Unfinished track", style = MaterialTheme.typography.titleMedium)
            Text(
                "Started ${formatDate(track.startedAtMs)} · ${formatDistance(track.distanceM)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("Resume") }
                OutlinedButton(onClick = onFinalise, modifier = Modifier.weight(1f)) {
                    Text("Finalise")
                }
            }
        }
    }
}

@Composable
private fun TrackCard(summary: TrackSummary, canDelete: Boolean, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = summary.name ?: formatDate(summary.startedAtMs),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatDate(summary.startedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDistance(summary.distanceM),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = formatDuration(summary.elapsedTimeS),
                    style = MaterialTheme.typography.bodySmall,
                )
                summary.gainDemM?.let {
                    Text(
                        text = "▲ ${formatDistance(it)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            if (canDelete) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun resumeOpenTrack(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START),
    )
}

/** Closes an open track with its currently stored summary figures (mitigation 5). */
private suspend fun finaliseOpenTrack(trackRepository: TrackRepository, openTrack: Track) {
    val track = trackRepository.getTrack(openTrack.id) ?: return
    if (track.endedAtMs != null) return
    trackRepository.finalizeTrack(
        trackId = track.id,
        endedAtMs = System.currentTimeMillis(),
        distanceM = track.distanceM,
        movingTimeS = track.movingTimeS,
        elapsedTimeS = track.elapsedTimeS,
        gainBaroM = track.gainBaroM,
        lossBaroM = track.lossBaroM,
        gainDemM = track.gainDemM,
        lossDemM = track.lossDemM,
        seaLevelHpa = track.seaLevelHpa,
        calibrated = track.calibrated,
    )
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
