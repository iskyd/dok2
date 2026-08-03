package dev.iskyd.dok2.recording

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.iskyd.dok2.Dok2Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The watchdog interval: the service re-arms this exact-and-allow-while-idle alarm on every
 * scheduled fire while an open track exists, so a process or service death is repaired within 3
 * minutes (DOCUMENTATION.md "Known device problems", mitigation 3). AlarmManager deliveries survive
 * process death, which is what makes the watchdog a real safety net.
 */
internal const val WATCHDOG_INTERVAL_MS: Long = 3 * 60 * 1000L

/** Request code for the watchdog alarm's [PendingIntent]; must stay stable so cancel matches. */
private const val WATCHDOG_REQUEST_CODE = 1

/**
 * Schedules the single-shot watchdog alarm 3 minutes from now. Call at recording start and again
 * from [WatchdogReceiver] while an open track exists.
 */
internal fun scheduleWatchdog(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
        watchdogPendingIntent(context),
    )
}

/** Cancels the pending watchdog alarm. Call when recording stops. */
internal fun cancelWatchdog(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.cancel(watchdogPendingIntent(context))
}

private fun watchdogPendingIntent(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        WATCHDOG_REQUEST_CODE,
        Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

/**
 * Fires every 3 minutes while a watchdog alarm is armed. If the recording service died (process
 * kill, START_STICKY miss, OEM background killer), restarts it with [RecordingService.ACTION_START]
 * so recording resumes into the still-open track. If there is no open track — the recording was
 * finalised cleanly and the alarm was cancelled — the receiver does nothing and does not re-arm.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as Dok2Application
                val openTrack = app.trackRepository.getOpenTrack()
                if (openTrack == null) return@launch
                // Keep the safety net armed for the next 3 minutes.
                scheduleWatchdog(context)
                if (!isServiceRunning(context)) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, RecordingService::class.java)
                            .setAction(RecordingService.ACTION_START),
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "watchdog check failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * True when [RecordingService] is alive in this process. Since API 26 `getRunningServices` only
     * returns the caller's own services, which is exactly the check we need.
     */
    private fun isServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(MAX_RUNNING_SERVICES).any {
            it.service.className == RecordingService::class.java.name
        }
    }

    private companion object {
        const val TAG = "WatchdogReceiver"
        const val MAX_RUNNING_SERVICES = 100
    }
}
