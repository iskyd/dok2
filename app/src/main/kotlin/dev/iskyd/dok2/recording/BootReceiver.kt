package dev.iskyd.dok2.recording

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
 * Restarts recording into the open track after a reboot (DOCUMENTATION.md "Known device problems",
 * mitigation 5). If the database is unavailable or the boot is still settling, the error is logged
 * and the 3-minute watchdog takes over; the device must never fail to boot because of this app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as Dok2Application
                val openTrack = app.trackRepository.getOpenTrack()
                if (openTrack != null) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, RecordingService::class.java)
                            .setAction(RecordingService.ACTION_START),
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "boot resume failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
