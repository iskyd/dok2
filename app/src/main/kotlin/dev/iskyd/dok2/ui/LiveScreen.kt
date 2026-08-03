package dev.iskyd.dok2.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.iskyd.dok2.domain.model.RecordingState
import dev.iskyd.dok2.recording.RecordingService
import dev.iskyd.dok2.recording.RecordingStateHolder
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The recording screen. It observes [RecordingStateHolder] and never writes recording state; the
 * buttons only send intent actions to [RecordingService], which owns the state machine.
 */
@Composable
fun LiveScreen() {
    val context = LocalContext.current
    val state by RecordingStateHolder.state.collectAsState()
    val distanceM by RecordingStateHolder.distanceM.collectAsState()
    val altitudeM by RecordingStateHolder.currentAltitudeM.collectAsState()
    val elevationGainM by RecordingStateHolder.elevationGainM.collectAsState()
    val elevationLossM by RecordingStateHolder.elevationLossM.collectAsState()
    val barometerCalibrated by RecordingStateHolder.barometerCalibrated.collectAsState()
    val movingTimeMs by RecordingStateHolder.movingTimeMs.collectAsState()
    val movingSegmentStartMs by RecordingStateHolder.movingTimeSegmentStartMs.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }

    val elapsedMs =
        produceState(0L, state, movingTimeMs, movingSegmentStartMs) {
            // Display-only clock. The service pushes moving time and the current segment start on
            // every transition; the live value is exactly moving time + the open recording segment,
            // so the clock survives screen changes (recomposition reads a fresh snapshot) and stays
            // frozen on pause. Touches no recording logic.
            if (state == RecordingState.Idle) {
                value = 0L
            } else {
                while (true) {
                    val segmentStart = movingSegmentStartMs
                    value =
                        if (state == RecordingState.Recording && segmentStart != null) {
                            movingTimeMs +
                                (System.currentTimeMillis() - segmentStart).coerceAtLeast(0)
                        } else {
                            movingTimeMs
                        }
                    delay(1_000L)
                }
            }
        }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stateLabel(state),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(text = formatDistance(distanceM), style = MaterialTheme.typography.headlineSmall)
        altitudeM?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    if (barometerCalibrated) {
                        "${it.toInt()} m"
                    } else {
                        "${it.toInt()} m · calibrating…"
                    },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        // Ascent/descent come from the same barometric accumulator and are only meaningful when a
        // barometric altitude exists; the row is hidden on barometer-less devices.
        val gainM = elevationGainM
        val lossM = elevationLossM
        if (altitudeM != null && gainM != null && lossM != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = if (barometerCalibrated) "▲ ${formatDistance(gainM)}" else "▲ —",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (barometerCalibrated) "▼ ${formatDistance(lossM)}" else "▼ —",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // The first minute of every recording the barometer solves its baseline against GNSS
        // altitudes; until then the shown altitude is a fallback estimate.
        if (state != RecordingState.Idle && !barometerCalibrated) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Altitude calibrating — needs ~1 min with clear sky.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatDuration(elapsedMs.value / 1000),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                RecordingState.Idle ->
                    Button(onClick = { sendAction(context, RecordingService.ACTION_START) }) {
                        Text("Start")
                    }
                RecordingState.ManualPaused ->
                    Button(
                        onClick = { sendAction(context, RecordingService.ACTION_PAUSE_RESUME) }
                    ) {
                        Text("Resume")
                    }
                else ->
                    Button(
                        onClick = { sendAction(context, RecordingService.ACTION_PAUSE_RESUME) }
                    ) {
                        Text("Pause")
                    }
            }
            if (state != RecordingState.Idle) {
                OutlinedButton(onClick = { confirmStop = true }) { Text("Stop") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text =
                "For best battery life, turn on airplane mode and re-enable " +
                    "GPS — the app needs no network at all.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop recording?") },
            text = { Text("The track will be finalised and the recording stopped.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStop = false
                        sendAction(context, RecordingService.ACTION_STOP)
                    }
                ) {
                    Text("Stop")
                }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

private fun sendAction(context: Context, action: String) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, RecordingService::class.java).setAction(action),
    )
}

private fun stateLabel(state: RecordingState): String =
    when (state) {
        RecordingState.Recording -> "RECORDING"
        RecordingState.AutoPaused -> "AUTO PAUSED"
        RecordingState.ManualPaused -> "PAUSED"
        RecordingState.Idle -> "IDLE"
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
