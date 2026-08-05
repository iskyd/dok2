package dev.iskyd.dok2.recording

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.iskyd.dok2.Dok2Application
import dev.iskyd.dok2.MainActivity
import dev.iskyd.dok2.R
import dev.iskyd.dok2.data.CoordinateCodec
import dev.iskyd.dok2.data.db.WaypointEntity
import dev.iskyd.dok2.data.repo.TrackRepository
import dev.iskyd.dok2.domain.elevation.Barometer
import dev.iskyd.dok2.domain.elevation.ElevationStats
import dev.iskyd.dok2.domain.filter.FilterChain
import dev.iskyd.dok2.domain.filter.FilterResult
import dev.iskyd.dok2.domain.model.GpsFix
import dev.iskyd.dok2.domain.model.PointState
import dev.iskyd.dok2.domain.model.RecordingState
import dev.iskyd.dok2.domain.model.TrackPoint
import dev.iskyd.dok2.domain.state.PauseEvent
import dev.iskyd.dok2.domain.state.RecordingStateMachine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

/**
 * The source of truth for recording state and the app's only location consumer.
 *
 * The service owns the full recording pipeline: GNSS fixes (GPS provider only, never network or
 * Play Services), barometer samples, the [FilterChain], the [RecordingStateMachine], and track
 * persistence through [TrackRepository]. The UI observes [RecordingStateHolder] and never writes
 * recording state; the service runs correctly with no UI process alive.
 *
 * Timestamps always come from the fix or the sensor, never from the clock at callback time
 * (AGENTS.md). The only `System.currentTimeMillis()` calls are for user-initiated actions, which
 * have no sensor timestamp.
 */
class RecordingService : Service() {

    private lateinit var app: Dok2Application

    private lateinit var trackRepository: TrackRepository

    private lateinit var locationManager: LocationManager

    private lateinit var sensorManager: SensorManager

    private var pressureSensor: Sensor? = null

    /** Runs all state-machine and persistence work; cancelled in [onDestroy]. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val stateMachine = RecordingStateMachine()

    private val filterChain = FilterChain()

    private val barometer = Barometer()

    private val elevationStats = ElevationStats()

    /** Points awaiting a batched [TrackRepository.appendPoints] write (10 points / ~30 s). */
    private val pointBuffer = mutableListOf<TrackPoint>()

    private var trackId: Long? = null

    private var latestBarometerPressure: Double? = null

    /**
     * Tracks the false→true edge of GNSS calibration so the accumulator is reseeded exactly once.
     */
    private var wasBarometerCalibrated = false

    /** True once the calibration phase finished; gates the "Start recording" action. */
    private var calibrationReady = false

    /** How the calibration phase ended, shown to the user; set together with [calibrationReady]. */
    private var calibrationOutcome: CalibrationOutcome? = null

    /**
     * Ends the calibration phase shortly after the window timeout even without a solved baseline.
     */
    private val calibrationTimeoutRunnable = Runnable {
        Log.d(TAG, "calibration window elapsed; ready to start recording")
        finishCalibration(CalibrationOutcome.TIMEOUT_FALLBACK)
    }

    /** The most recent fix, used for the Waypoint action. */
    private var lastFix: GpsFix? = null

    private var requestedIntervalMs: Long = RecordingStateMachine.GNSS_INTERVAL_MS

    private var waypointFlashUntilMs = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val revertWaypointFlash = Runnable { updateNotification() }

    override fun onCreate() {
        super.onCreate()
        app = application as Dok2Application
        trackRepository = app.trackRepository
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        RecordingNotifications.ensureChannel(this)
        stateMachine.addListener(::onStateTransition)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceCompat()
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_BEGIN_RECORDING -> beginRecordingAfterCalibration()
            ACTION_PAUSE_RESUME -> togglePauseResume()
            ACTION_WAYPOINT -> saveWaypoint()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        sensorManager.unregisterListener(pressureListener)
        locationManager.removeUpdates(locationListener)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Recording lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts recording: resuming into an existing open track (reboot, watchdog restart, library
     * resume) or creating a new one. On barometer devices a fresh recording enters the calibration
     * phase first (see [beginCalibrationSession]); recording proper begins on
     * ACTION_BEGIN_RECORDING.
     */
    private fun startRecording() {
        val currentTrackId = trackId
        if (currentTrackId != null) {
            Log.d(TAG, "start requested while recording into track $currentTrackId; ignoring")
            return
        }
        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()
                val openTrack = trackRepository.getOpenTrack()
                if (openTrack != null) {
                    // Resuming an open track: recording starts immediately — the calibration gate
                    // only applies to fresh starts, and after a reboot there is no UI alive to
                    // press the start button.
                    trackId = openTrack.id
                    RecordingStateHolder.setOpenTrackId(openTrack.id)
                    beginRecordingSession(openTrack.id, now, fromCalibration = false)
                } else {
                    val id = trackRepository.startTrack(now, name = null)
                    trackId = id
                    RecordingStateHolder.setOpenTrackId(id)
                    if (pressureSensor != null) {
                        beginCalibrationSession(now)
                    } else {
                        beginRecordingSession(id, now, fromCalibration = false)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "failed to start recording", error)
                stopSelf()
            }
        }
    }

    /**
     * Begins the recording proper into [id]. When [fromCalibration] the barometer baseline solved
     * in the calibration phase is preserved; otherwise every pipeline is reset and the barometer
     * calibrates in the background during recording, as before the calibration phase existed.
     */
    private fun beginRecordingSession(id: Long, now: Long, fromCalibration: Boolean) {
        if (!fromCalibration) {
            resetPipelines()
        }
        handler.removeCallbacks(calibrationTimeoutRunnable)
        if (fromCalibration) {
            stateMachine.beginRecording(now)
        } else {
            stateMachine.start(now)
        }
        RecordingStateHolder.setCalibrationReady(false)
        RecordingStateHolder.setCalibrationOutcome(null)
        scheduleWatchdog(this)
        registerPressureSensor()
        requestLocationUpdates(requestedIntervalMs)
        updateNotification()
        Log.d(TAG, "recording started into track $id")
    }

    /**
     * Runs the calibration phase: sensors and GNSS feed the barometer so it can solve its baseline,
     * but no trackpoints are recorded and the timer does not run. Ends early when the baseline is
     * solved or at the window timeout (see [finishCalibration]); recording proper begins on
     * ACTION_BEGIN_RECORDING, or never — cancel tears down and deletes the empty track.
     */
    private fun beginCalibrationSession(now: Long) {
        resetPipelines()
        stateMachine.startCalibration(now)
        calibrationReady = false
        calibrationOutcome = null
        RecordingStateHolder.setCalibrationReady(false)
        RecordingStateHolder.setCalibrationOutcome(null)
        barometer.start(now)
        scheduleWatchdog(this)
        registerPressureSensor()
        requestLocationUpdates(requestedIntervalMs)
        handler.removeCallbacks(calibrationTimeoutRunnable)
        // The grace lets the first post-window fix land and solve the baseline before the
        // fallback outcome is declared, so the success/timeout insight is accurate.
        handler.postDelayed(
            calibrationTimeoutRunnable,
            CALIBRATION_TIMEOUT_MS + CALIBRATION_GRACE_MS,
        )
        updateNotification()
        Log.d(TAG, "calibration phase started")
    }

    /**
     * ACTION_BEGIN_RECORDING handler: starts recording once the calibration phase is over. Ignored
     * unless calibrating and [calibrationReady] — the user decision was to wait for calibration (or
     * its timeout) before starting.
     */
    private fun beginRecordingAfterCalibration() {
        val id = trackId ?: return
        if (stateMachine.state != RecordingState.Calibrating) return
        if (!calibrationReady) {
            Log.d(TAG, "begin recording requested before calibration ready; ignoring")
            return
        }
        beginRecordingSession(id, System.currentTimeMillis(), fromCalibration = true)
    }

    private fun finishCalibration(outcome: CalibrationOutcome) {
        if (calibrationReady) return
        calibrationReady = true
        calibrationOutcome = outcome
        handler.removeCallbacks(calibrationTimeoutRunnable)
        RecordingStateHolder.setCalibrationReady(true)
        RecordingStateHolder.setCalibrationOutcome(outcome)
        updateNotification()
    }

    private fun resetPipelines() {
        filterChain.reset()
        barometer.reset()
        elevationStats.reset()
        pointBuffer.clear()
        latestBarometerPressure = null
        wasBarometerCalibrated = false
        lastFix = null
        requestedIntervalMs = RecordingStateMachine.GNSS_INTERVAL_MS
        RecordingStateHolder.setDistance(0.0)
        RecordingStateHolder.setCurrentAltitude(null)
        RecordingStateHolder.setLastLatLng(null)
        RecordingStateHolder.setLastFixMs(null)
    }

    private fun togglePauseResume() {
        val id = trackId ?: return
        val now = System.currentTimeMillis()
        when (stateMachine.state) {
            RecordingState.ManualPaused -> stateMachine.userResume(now)
            RecordingState.Recording,
            RecordingState.AutoPaused -> stateMachine.userPause(now)
            RecordingState.Calibrating,
            RecordingState.Idle -> return
        }
        refreshLocationUpdateInterval()
        updateNotification()
    }

    private fun saveWaypoint() {
        val id = trackId ?: return
        val fix = lastFix ?: return
        val database = app.database
        serviceScope.launch {
            try {
                database
                    .waypointDao()
                    .insert(
                        WaypointEntity(
                            trackId = id,
                            tMs = fix.tMs,
                            latE7 = CoordinateCodec.toE7(fix.latDeg),
                            lonE7 = CoordinateCodec.toE7(fix.lonDeg),
                        )
                    )
            } catch (error: Exception) {
                Log.e(TAG, "waypoint insert failed", error)
            }
        }
        // Simplest correct confirmation: flash the text in the main notification for 3 seconds.
        waypointFlashUntilMs = System.currentTimeMillis() + WAYPOINT_FLASH_MS
        handler.removeCallbacks(revertWaypointFlash)
        handler.postDelayed(revertWaypointFlash, WAYPOINT_FLASH_MS)
        updateNotification()
    }

    private fun stopRecording() {
        val id = trackId ?: return
        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()
                if (stateMachine.state == RecordingState.Calibrating) {
                    // Cancelled during calibration: the track has no points yet, delete it whole.
                    stateMachine.stop(now)
                    trackRepository.deleteTrack(id)
                    teardownRecording()
                    return@launch
                }
                stateMachine.stop(now)
                val remaining = pointBuffer.toList()
                pointBuffer.clear()
                trackRepository.appendPoints(id, remaining)
                finalizeTrack(id, now)
                teardownRecording()
            } catch (error: Exception) {
                Log.e(TAG, "failed to finalise track $id", error)
                teardownRecording()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fix pipeline
    // ---------------------------------------------------------------------------------------------

    private fun handleFix(location: Location) {
        val fix =
            GpsFix(
                tMs = location.time,
                latDeg = location.latitude,
                lonDeg = location.longitude,
                accuracyM = location.accuracy.toDouble(),
                altGnssM = if (location.hasAltitude()) location.altitude else null,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                pressureHpa = latestBarometerPressure,
            )
        lastFix = fix

        val altGnssM = fix.altGnssM
        if (altGnssM != null) {
            barometer.onGnssAltitude(fix.tMs, altGnssM, fix.accuracyM)
        }

        if (stateMachine.state == RecordingState.Calibrating) {
            // Calibration phase: fixes feed the barometer baseline only; nothing is recorded or
            // accumulated. The UI still gets the live fallback altitude.
            RecordingStateHolder.setLastFixMs(fix.tMs)
            RecordingStateHolder.setCurrentAltitude(barometer.currentBarometricAltitudeM)
            RecordingStateHolder.setBarometerCalibrated(barometer.calibrated)
            RecordingStateHolder.setLastLatLng(LatLng(fix.latDeg, fix.lonDeg))
            if (barometer.calibrated) {
                finishCalibration(CalibrationOutcome.SUCCESS)
            }
            return
        }

        val id = trackId ?: return
        val result = filterChain.onFix(fix, stateMachine.state.pointState ?: PointState.RECORDING)
        when (result) {
            is FilterResult.Accepted -> {
                stateMachine.fixAccepted(fix.tMs)
                bufferPoint(result.point)
            }
            is FilterResult.Stationary -> bufferPoint(result.point)
            is FilterResult.Rejected ->
                Log.d(TAG, "fix rejected at ${fix.tMs}: ${result.rejectionReason}")
        }

        // Gain/loss accumulate only in RECORDING; elapsed time accumulates in all non-idle states.
        // When GNSS calibration completes, the baseline snaps and the next sample would record a
        // phantom step of the jump size; reseeding re-anchors the reference first.
        if (barometer.calibrated && !wasBarometerCalibrated) {
            barometer.currentBarometricAltitudeM?.let { elevationStats.reseed(it) }
        }
        wasBarometerCalibrated = barometer.calibrated
        if (stateMachine.state == RecordingState.Recording) {
            barometer.currentBarometricAltitudeM?.let { elevationStats.add(it) }
        }

        stateMachine.autoPauseTimeout(fix.tMs)

        RecordingStateHolder.setLastFixMs(fix.tMs)
        RecordingStateHolder.setDistance(filterChain.accumulatedDistanceM)
        RecordingStateHolder.setCurrentAltitude(barometer.currentBarometricAltitudeM)
        RecordingStateHolder.setBarometerCalibrated(barometer.calibrated)
        // Gain/loss mirror the 5 m hysteresis accumulator at the same cadence as distance; the
        // values are already frozen while paused because the accumulator only runs in RECORDING.
        RecordingStateHolder.setElevationGainM(elevationStats.gainM)
        RecordingStateHolder.setElevationLossM(elevationStats.lossM)
        RecordingStateHolder.setLastLatLng(LatLng(fix.latDeg, fix.lonDeg))

        refreshLocationUpdateInterval()
    }

    private fun bufferPoint(point: TrackPoint) {
        pointBuffer += point
        if (pointBuffer.size >= POINT_BUFFER_SIZE) {
            flushPoints()
        }
    }

    private fun flushPoints() {
        val id = trackId ?: return
        if (pointBuffer.isEmpty()) return
        val batch = pointBuffer.toList()
        pointBuffer.clear()
        serviceScope.launch {
            try {
                trackRepository.appendPoints(id, batch)
            } catch (error: Exception) {
                Log.e(TAG, "point batch persistence failed", error)
            }
        }
    }

    /**
     * Requests location updates at the interval the state machine currently wants (3 s, dropping to
     * 30 s after 2 minutes of manual pause). Re-requested whenever that interval changes.
     */
    private fun requestLocationUpdates(minTimeMs: Long) {
        try {
            locationManager.removeUpdates(locationListener)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                0f,
                locationListener,
                Looper.getMainLooper(),
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "location permission unavailable; stopping recording", error)
            stopRecording()
        }
    }

    private fun refreshLocationUpdateInterval() {
        val now = System.currentTimeMillis()
        val interval = stateMachine.gnssIntervalMsAt(now)
        if (interval != requestedIntervalMs) {
            requestedIntervalMs = interval
            requestLocationUpdates(interval)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Finalisation
    // ---------------------------------------------------------------------------------------------

    private suspend fun finalizeTrack(id: Long, endedAtMs: Long) {
        val points = trackRepository.getPoints(id)
        val demDirectory = File(filesDir, "dem").apply { mkdirs() }
        val altDemBySeq = LinkedHashMap<Long, Double>()
        for ((seq, point) in points.withIndex()) {
            // DemTileRepository caches the last loaded cell, so per-point lookups are cheap.
            val reader =
                app.demTileRepository.loadHgt(point.latDeg, point.lonDeg, demDirectory) ?: continue
            val altDemM = reader.altitudeAt(point.latDeg, point.lonDeg)
            if (altDemM != null) {
                altDemBySeq[seq.toLong()] = altDemM
            }
        }
        trackRepository.updateDemAltitudes(id, altDemBySeq)

        val demStats = ElevationStats()
        for (altDemM in altDemBySeq.values) {
            demStats.add(altDemM)
        }
        val hasDem = altDemBySeq.isNotEmpty()
        val barometerFed = elevationStats.referenceM != null

        trackRepository.finalizeTrack(
            trackId = id,
            endedAtMs = endedAtMs,
            distanceM = filterChain.accumulatedDistanceM,
            movingTimeS = stateMachine.movingTimeMs / 1000,
            elapsedTimeS = stateMachine.elapsedTimeMs / 1000,
            gainBaroM = if (barometerFed) elevationStats.gainM else null,
            lossBaroM = if (barometerFed) elevationStats.lossM else null,
            gainDemM = if (hasDem) demStats.gainM else null,
            lossDemM = if (hasDem) demStats.lossM else null,
            seaLevelHpa = barometer.seaLevelHpa,
            calibrated = barometer.calibrated,
        )
    }

    private fun teardownRecording() {
        cancelWatchdog(this)
        handler.removeCallbacks(calibrationTimeoutRunnable)
        calibrationReady = false
        calibrationOutcome = null
        sensorManager.unregisterListener(pressureListener)
        locationManager.removeUpdates(locationListener)
        pointBuffer.clear()
        trackId = null
        lastFix = null
        latestBarometerPressure = null
        requestedIntervalMs = RecordingStateMachine.GNSS_INTERVAL_MS
        RecordingStateHolder.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------------------------------------
    // Sensors
    // ---------------------------------------------------------------------------------------------

    private fun registerPressureSensor() {
        val sensor = pressureSensor ?: return
        val registered =
            sensorManager.registerListener(pressureListener, sensor, SensorManager.SENSOR_DELAY_UI)
        if (!registered) {
            Log.w(TAG, "pressure sensor registration failed")
        }
    }

    private fun unregisterPressureSensor() {
        sensorManager.unregisterListener(pressureListener)
    }

    // ---------------------------------------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------------------------------------

    private val locationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleFix(location)
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in the framework; no longer delivered on modern Android.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

    private val pressureListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Sensor timestamps are nanoseconds since boot; convert to epoch millis.
                val tMs =
                    (event.timestamp / 1_000_000L) +
                        (System.currentTimeMillis() - SystemClock.elapsedRealtime())
                val pressureHpa = event.values[0].toDouble()
                latestBarometerPressure = pressureHpa
                barometer.onPressure(tMs, pressureHpa)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

    /**
     * Persists every state-machine transition to `pause_events` and mirrors it to
     * [RecordingStateHolder]. Transitions whose new state has no [PointState] code — Idle (stop is
     * handled by finalisation) and Calibrating (no points exist during calibration) — are skipped.
     */
    private fun onStateTransition(event: PauseEvent) {
        val id = trackId
        if (id != null) {
            val pointState = event.newState.pointState
            if (pointState != null) {
                serviceScope.launch {
                    try {
                        trackRepository.appendPauseEvents(id, listOf(event.tMs to pointState))
                    } catch (error: Exception) {
                        Log.e(TAG, "pause event persistence failed", error)
                    }
                }
            }
        }
        RecordingStateHolder.setState(event.newState)
        // Moving time + segment anchor for the Live screen's clock; the anchor is what keeps the
        // clock exact between transitions, so the two must always be pushed together.
        RecordingStateHolder.setMovingTimeMs(stateMachine.movingTimeMs)
        RecordingStateHolder.setMovingTimeSegmentStartMs(stateMachine.segmentStartMs)
        if (id != null) {
            updateNotification()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Foreground notification
    // ---------------------------------------------------------------------------------------------

    private fun startForegroundServiceCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            // API 29-33 use the two-arg form; the manifest declares the location type.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val calibrating = stateMachine.state == RecordingState.Calibrating
        val contentText =
            when {
                System.currentTimeMillis() < waypointFlashUntilMs -> "Waypoint saved"
                calibrating && !calibrationReady -> "Calibrating altitude…"
                calibrating && calibrationOutcome == CalibrationOutcome.SUCCESS ->
                    "Baseline solved — start recording"
                calibrating -> "Standard baseline in use — start recording"
                stateMachine.state == RecordingState.Recording -> "Recording"
                stateMachine.state == RecordingState.AutoPaused -> "Auto-paused"
                stateMachine.state == RecordingState.ManualPaused -> "Paused"
                else -> "Idle"
            }
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            NotificationCompat.Builder(this, RecordingNotifications.CHANNEL_RECORDING)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        if (calibrating) {
            builder.addAction(0, "Start recording", servicePendingIntent(ACTION_BEGIN_RECORDING))
            builder.addAction(0, "Cancel", servicePendingIntent(ACTION_STOP))
        } else {
            builder.addAction(
                0,
                if (stateMachine.state == RecordingState.ManualPaused) "Resume" else "Pause",
                servicePendingIntent(ACTION_PAUSE_RESUME),
            )
            builder.addAction(0, "Waypoint", servicePendingIntent(ACTION_WAYPOINT))
            builder.addAction(0, "Stop", servicePendingIntent(ACTION_STOP))
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun updateNotification() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val ACTION_START = "dev.iskyd.dok2.recording.action.START"
        const val ACTION_BEGIN_RECORDING = "dev.iskyd.dok2.recording.action.BEGIN_RECORDING"
        const val ACTION_PAUSE_RESUME = "dev.iskyd.dok2.recording.action.PAUSE_RESUME"
        const val ACTION_WAYPOINT = "dev.iskyd.dok2.recording.action.WAYPOINT"
        const val ACTION_STOP = "dev.iskyd.dok2.recording.action.STOP"
        const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"
        private const val POINT_BUFFER_SIZE = 10
        private const val WAYPOINT_FLASH_MS = 3_000L

        /** Matches [Barometer.Config.calibrationWindowMs]; the phase lasts at most one minute. */
        private const val CALIBRATION_TIMEOUT_MS = 60_000L

        /**
         * Head start for the post-window fix to solve the baseline before the fallback is declared.
         */
        private const val CALIBRATION_GRACE_MS = 5_000L
    }
}
