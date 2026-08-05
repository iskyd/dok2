package dev.iskyd.dok2.recording

import dev.iskyd.dok2.domain.model.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.geometry.LatLng

/** How the calibration phase ended, surfaced to the user before recording starts. */
enum class CalibrationOutcome {
    /** The barometer baseline was solved against GNSS altitudes. */
    SUCCESS,

    /** The 60 s window elapsed without a usable GNSS altitude; the standard baseline is in use. */
    TIMEOUT_FALLBACK,
}

/**
 * The process-wide holder of recording state, updated only by [RecordingService] and read by the
 * UI. It survives activity and composition recreation because it is a process-scoped singleton; the
 * UI observes it via [state] and never writes recording state.
 *
 * The setters are [internal] so that only the service (same module) can mutate the flows. The
 * last-known fix position and per-fix figures are also exposed here so the Live and Map screens can
 * render without touching the service.
 */
object RecordingStateHolder {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)

    /** The current recording state; the single source of truth the UI renders. */
    val state: StateFlow<RecordingState> = _state

    private val _lastFixMs = MutableStateFlow<Long?>(null)

    /** Epoch millis of the most recent GNSS fix, or null before the first fix. */
    val lastFixMs: StateFlow<Long?> = _lastFixMs

    private val _openTrackId = MutableStateFlow<Long?>(null)

    /** The id of the track recording is currently being written into, or null when idle. */
    val openTrackId: StateFlow<Long?> = _openTrackId

    private val _distanceM = MutableStateFlow(0.0)

    /** Odometer distance in metres, mirrored from the filter chain. */
    val distanceM: StateFlow<Double> = _distanceM

    private val _currentAltitudeM = MutableStateFlow<Double?>(null)

    /** The current barometric altitude in metres, or null before the first pressure sample. */
    val currentAltitudeM: StateFlow<Double?> = _currentAltitudeM

    private val _barometerCalibrated = MutableStateFlow(false)

    /**
     * True once the barometer baseline has been solved against GNSS altitudes. While false the
     * displayed altitude uses the fallback sea-level pressure and can be off by tens to hundreds of
     * metres; the UI shows a calibrating hint.
     */
    val barometerCalibrated: StateFlow<Boolean> = _barometerCalibrated

    private val _calibrationReady = MutableStateFlow(false)

    /**
     * True while in the [RecordingState.Calibrating] phase once the barometer baseline is solved or
     * the calibration window elapsed, meaning the "Start recording" action is available. False in
     * every other state.
     */
    val calibrationReady: StateFlow<Boolean> = _calibrationReady

    private val _calibrationOutcome = MutableStateFlow<CalibrationOutcome?>(null)

    /**
     * How the calibration phase ended: [CalibrationOutcome.SUCCESS] once the baseline was solved,
     * [CalibrationOutcome.TIMEOUT_FALLBACK] when the 60 s window elapsed without one. Null while
     * the phase is still running (or outside it).
     */
    val calibrationOutcome: StateFlow<CalibrationOutcome?> = _calibrationOutcome

    private val _elevationGainM = MutableStateFlow<Double?>(null)

    /**
     * Accumulated barometric ascent in metres for the current track, or null before the first fix.
     * Mirrors the 5 m hysteresis accumulator and freezes while paused, exactly like [distanceM].
     */
    val elevationGainM: StateFlow<Double?> = _elevationGainM

    private val _elevationLossM = MutableStateFlow<Double?>(null)

    /** Accumulated barometric descent in metres; same lifecycle as [elevationGainM]. */
    val elevationLossM: StateFlow<Double?> = _elevationLossM

    private val _lastLatLng = MutableStateFlow<LatLng?>(null)

    /** The most recent GNSS position, for the map's camera-follow. */
    val lastLatLng: StateFlow<LatLng?> = _lastLatLng

    private val _movingTimeMs = MutableStateFlow(0L)

    /**
     * Moving time in milliseconds, snapshotted by the service at every state transition. Frozen
     * while paused: the paused clock renders this value without ticking.
     */
    val movingTimeMs: StateFlow<Long> = _movingTimeMs

    private val _movingTimeSegmentStartMs = MutableStateFlow<Long?>(null)

    /**
     * Epoch millis the current state segment started, pushed alongside [movingTimeMs]. While
     * recording, the exact live clock is `movingTimeMs + (now - movingTimeSegmentStartMs)`, which
     * stays correct across composition recreation without the UI accumulating anything.
     */
    val movingTimeSegmentStartMs: StateFlow<Long?> = _movingTimeSegmentStartMs

    internal fun setState(newState: RecordingState) {
        _state.value = newState
    }

    internal fun setMovingTimeMs(tMs: Long) {
        _movingTimeMs.value = tMs
    }

    internal fun setMovingTimeSegmentStartMs(tMs: Long?) {
        _movingTimeSegmentStartMs.value = tMs
    }

    internal fun setLastFixMs(tMs: Long?) {
        _lastFixMs.value = tMs
    }

    internal fun setOpenTrackId(id: Long?) {
        _openTrackId.value = id
    }

    internal fun setDistance(distanceM: Double) {
        _distanceM.value = distanceM
    }

    internal fun setCurrentAltitude(altitudeM: Double?) {
        _currentAltitudeM.value = altitudeM
    }

    internal fun setBarometerCalibrated(calibrated: Boolean) {
        _barometerCalibrated.value = calibrated
    }

    internal fun setCalibrationReady(ready: Boolean) {
        _calibrationReady.value = ready
    }

    internal fun setCalibrationOutcome(outcome: CalibrationOutcome?) {
        _calibrationOutcome.value = outcome
    }

    internal fun setElevationGainM(gainM: Double?) {
        _elevationGainM.value = gainM
    }

    internal fun setElevationLossM(lossM: Double?) {
        _elevationLossM.value = lossM
    }

    internal fun setLastLatLng(latLng: LatLng?) {
        _lastLatLng.value = latLng
    }

    /** Clears all fields; called by the service when recording stops. */
    internal fun reset() {
        _state.value = RecordingState.Idle
        _lastFixMs.value = null
        _openTrackId.value = null
        _distanceM.value = 0.0
        _currentAltitudeM.value = null
        _barometerCalibrated.value = false
        _calibrationReady.value = false
        _calibrationOutcome.value = null
        _elevationGainM.value = null
        _elevationLossM.value = null
        _lastLatLng.value = null
        _movingTimeMs.value = 0L
        _movingTimeSegmentStartMs.value = null
    }
}
