package dev.iskyd.dok2.domain.model

/**
 * The recording state of the app, matching the state machine in DOCUMENTATION.md.
 *
 * [Idle] is the initial and final state. [Calibrating] is entered after a fresh start while the
 * barometer solves its baseline against GNSS altitudes; no points are captured and the timer does
 * not run. [Recording] accumulates; [AutoPaused] pauses after 60 s of no accepted displacement and
 * resumes automatically on the next accepted displacement; [ManualPaused] is entered and left only
 * by the user, and suspends the auto-pause heuristic while active.
 */
sealed interface RecordingState {
    data object Idle : RecordingState

    data object Calibrating : RecordingState

    data object Recording : RecordingState

    data object AutoPaused : RecordingState

    data object ManualPaused : RecordingState

    /**
     * The [PointState] used to tag trackpoints captured in this state, or null for [Idle] and
     * [Calibrating], in which no points are recorded.
     */
    val pointState: PointState?
        get() =
            when (this) {
                Idle -> null
                Calibrating -> null
                Recording -> PointState.RECORDING
                AutoPaused -> PointState.AUTO_PAUSED
                ManualPaused -> PointState.MANUAL_PAUSED
            }
}
