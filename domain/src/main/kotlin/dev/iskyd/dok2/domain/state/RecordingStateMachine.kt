package dev.iskyd.dok2.domain.state

import dev.iskyd.dok2.domain.model.RecordingState

/**
 * A recording state transition, emitted to the listeners of [RecordingStateMachine] so the app
 * layer can persist it to the `pause_events` table. Every transition emits one of these, so a track
 * is exactly reconstructable from the database.
 *
 * @property tMs the epoch millis at which the transition happened.
 * @property newState the state entered by the transition.
 */
data class PauseEvent(val tMs: Long, val newState: RecordingState)

/**
 * The recording state machine from DOCUMENTATION.md §Recording state machine.
 *
 * ```
 *                  start
 *         IDLE ──────────────► RECORDING ◄─────────┐
 *                                 │  ▲             │
 *         no displacement 60 s    │  │ displacement│ resume
 *                                 ▼  │             │
 *                           AUTO_PAUSED            │
 *                                                  │
 *         RECORDING ──user pause──► MANUAL_PAUSED ─┘
 *         AUTO_PAUSED ──user pause──► MANUAL_PAUSED
 *
 *         any state ──stop──► IDLE (track finalised)
 * ```
 *
 * Pure logic, no timers, no Android: the service drives it with events carrying their own
 * timestamps ([start], [fixAccepted], [autoPauseTimeout], [userPause], [userResume], [stop]).
 *
 * Rules:
 * - Auto-pause triggers after 60 s with no accepted displacement and clears automatically on the
 *   next accepted displacement.
 * - Manual pause is entered and left only by the user; **auto-pause logic is suspended while
 *   manually paused** — movement does not silently resume a manually paused track (the user's
 *   intent overrides the heuristic).
 * - Moving time accumulates only in [RecordingState.Recording]; elapsed time accumulates in all
 *   non-idle states.
 * - After 2 minutes in [RecordingState.ManualPaused] the GNSS interval drops from 3 s to 30 s (the
 *   trickle — GNSS is never switched off) and is restored on resume.
 */
class RecordingStateMachine {

    /** The current state. */
    var state: RecordingState = RecordingState.Idle
        private set

    /** Accumulated moving time in milliseconds (only time spent in [RecordingState.Recording]). */
    val movingTimeMs: Long
        get() = movingTimeMsValue

    /** Accumulated elapsed time in milliseconds (all time spent in non-idle states). */
    val elapsedTimeMs: Long
        get() = elapsedTimeMsValue

    /** Epoch millis the current recording started, or null while idle. */
    val recordingStartedAtMs: Long?
        get() = recordingStartedAtMsValue

    /**
     * Epoch millis the current state segment started. While [RecordingState.Recording] the segment
     * is the in-progress moving-time span, so the live clock can render exactly as
     * `movingTimeMs + (now - segmentStartMs)` without a UI-side accumulator.
     */
    val segmentStartMs: Long?
        get() = segmentStartMsValue

    private val listeners = mutableListOf<(PauseEvent) -> Unit>()

    private var movingTimeMsValue: Long = 0L
    private var elapsedTimeMsValue: Long = 0L
    private var recordingStartedAtMsValue: Long? = null
    private var segmentStartMsValue: Long? = null
    private var lastEventAtMs: Long = 0L
    private var lastAcceptedDisplacementAtMs: Long? = null
    private var manualPausedAtMs: Long? = null

    /** Registers a callback invoked with a [PauseEvent] on every state transition. */
    fun addListener(listener: (PauseEvent) -> Unit) {
        listeners += listener
    }

    /** Starts a new recording: IDLE → RECORDING. No-op (returns false) unless idle. */
    fun start(tMs: Long): Boolean {
        if (state != RecordingState.Idle) return false
        advance(tMs)
        recordingStartedAtMsValue = tMs
        state = RecordingState.Recording
        emit(state, tMs)
        return true
    }

    /**
     * The filter chain accepted a fix that advanced the anchor. Resets the auto-pause timer, and if
     * currently auto-paused, auto-resumes to RECORDING. Movement does **not** resume a manual
     * pause.
     */
    fun fixAccepted(tMs: Long): Boolean {
        if (state == RecordingState.Idle) return false
        advance(tMs)
        lastAcceptedDisplacementAtMs = tMs
        if (state == RecordingState.AutoPaused) {
            transitionTo(RecordingState.Recording, tMs)
            return true
        }
        return false
    }

    /**
     * The service's periodic check that 60 s elapsed with no accepted displacement. RECORDING →
     * AUTO_PAUSED. No-op while not recording or when the delay has not yet elapsed; the machine
     * verifies the precondition itself.
     */
    fun autoPauseTimeout(tMs: Long): Boolean {
        if (state != RecordingState.Recording) return false
        val reference = lastAcceptedDisplacementAtMs ?: recordingStartedAtMsValue ?: return false
        if (tMs - reference < AUTO_PAUSE_DELAY_MS) return false
        advance(tMs)
        transitionTo(RecordingState.AutoPaused, tMs)
        return true
    }

    /**
     * User-initiated pause: RECORDING or AUTO_PAUSED → MANUAL_PAUSED. No-op while already manually
     * paused.
     */
    fun userPause(tMs: Long): Boolean {
        when (state) {
            RecordingState.Idle -> return false
            RecordingState.ManualPaused -> {
                advance(tMs)
                return false
            }
            RecordingState.Recording,
            RecordingState.AutoPaused -> {
                advance(tMs)
                manualPausedAtMs = tMs
                transitionTo(RecordingState.ManualPaused, tMs)
                return true
            }
        }
    }

    /**
     * User-initiated resume: MANUAL_PAUSED → RECORDING. No-op in any other state — movement never
     * auto-resumes a manual pause, only this call does.
     */
    fun userResume(tMs: Long): Boolean {
        if (state != RecordingState.ManualPaused) {
            advance(tMs)
            return false
        }
        advance(tMs)
        transitionTo(RecordingState.Recording, tMs)
        return true
    }

    /** Stops the recording and finalises the track: any state → IDLE. No-op while idle. */
    fun stop(tMs: Long): Boolean {
        if (state == RecordingState.Idle) return false
        advance(tMs)
        segmentStartMsValue = null
        transitionTo(RecordingState.Idle, tMs)
        return true
    }

    /**
     * The GNSS request interval to use right now: 3 s normally, dropping to 30 s once 2 minutes
     * have been spent in [RecordingState.ManualPaused]. The trickle keeps ephemeris data fresh so
     * resume is a warm reacquisition; GNSS is never switched off.
     */
    fun gnssIntervalMsAt(nowMs: Long): Long {
        val pausedAt = manualPausedAtMs ?: return GNSS_INTERVAL_MS
        if (state != RecordingState.ManualPaused) return GNSS_INTERVAL_MS
        return if (nowMs - pausedAt >= TRICKLE_DELAY_MS) TRICKLE_INTERVAL_MS else GNSS_INTERVAL_MS
    }

    /** The GNSS interval evaluated at the time of the most recent event. */
    val gnssIntervalMs: Long
        get() = gnssIntervalMsAt(lastEventAtMs)

    private fun emit(newState: RecordingState, tMs: Long) {
        val event = PauseEvent(tMs = tMs, newState = newState)
        for (listener in listeners) {
            listener(event)
        }
    }

    private fun transitionTo(newState: RecordingState, tMs: Long) {
        state = newState
        emit(newState, tMs)
    }

    /** Closes the current state segment at [atMs], rolling accumulated time forward. */
    private fun advance(atMs: Long) {
        val segmentStart = segmentStartMsValue
        if (segmentStart != null) {
            val duration = atMs - segmentStart
            if (duration > 0) {
                if (state == RecordingState.Recording) {
                    movingTimeMsValue += duration
                }
                if (state != RecordingState.Idle) {
                    elapsedTimeMsValue += duration
                }
            }
        }
        segmentStartMsValue = atMs
        lastEventAtMs = atMs
    }

    companion object {
        /** Normal GNSS request interval, 3 s. */
        const val GNSS_INTERVAL_MS: Long = 3_000L

        /** Trickle interval after 2 minutes of manual pause, 30 s. */
        const val TRICKLE_INTERVAL_MS: Long = 30_000L

        /** Auto-pause triggers after 60 s with no accepted displacement. */
        const val AUTO_PAUSE_DELAY_MS: Long = 60_000L

        /** GNSS drops to the trickle interval after 2 minutes of manual pause. */
        const val TRICKLE_DELAY_MS: Long = 120_000L
    }
}
