package dev.iskyd.dok2.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Notification channels and ids used by the recording foreground service. Channel creation is
 * idempotent and must happen before the first [android.app.Notification] referencing the channel is
 * posted.
 */
object RecordingNotifications {

    /**
     * Channel for the recording-status notification and its Pause/Resume, Waypoint, Stop actions.
     */
    const val CHANNEL_RECORDING = "recording"

    /** The foreground-service notification id. */
    const val NOTIFICATION_ID_RECORDING = 1

    /**
     * Creates the "recording" channel (importance LOW — status-only, no sound). Safe to call more
     * than once; creating an existing channel is a no-op.
     */
    fun ensureChannel(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Recording status and controls" }
        notificationManager.createNotificationChannel(channel)
    }
}
