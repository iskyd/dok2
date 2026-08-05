package dev.iskyd.dok2.domain.state

import com.google.common.truth.Truth.assertThat
import dev.iskyd.dok2.domain.TestFixtures
import org.junit.Test

/**
 * Replay of slow-ascent.jsonl (synthetic steady ascent, 3 s cadence, 30 min) with the calibration
 * phase in front of it: locks the contract that the calibration window is not recording time.
 *
 * In the real app, fixes inside the 60 s calibration window feed the barometer only — they never
 * reach the state machine. This test replays the hike with `beginRecording` 60 s after `start`, so
 * elapsed and moving time must equal the hike length, not the hike length plus the calibration
 * window. Without the Calibrating exclusion in `advance()`, this test fails by exactly 60 s.
 */
class RecordingStateMachineReplayTest {

    @Test
    fun `elapsed and moving time exclude the calibration window`() {
        val fixes = TestFixtures.loadFixture("slow-ascent.jsonl")
        val machine = RecordingStateMachine()
        val beginTMs = 60_000L
        val lastTMs = fixes.last().tMs

        machine.startCalibration(fixes.first().tMs)
        machine.beginRecording(beginTMs)
        for (fix in fixes) {
            if (fix.tMs >= beginTMs) {
                machine.fixAccepted(fix.tMs)
            }
        }
        machine.stop(lastTMs)

        val expectedMs = lastTMs - beginTMs
        assertThat(machine.elapsedTimeMs).isEqualTo(expectedMs)
        assertThat(machine.movingTimeMs).isEqualTo(expectedMs)
        assertThat(machine.recordingStartedAtMs).isEqualTo(beginTMs)
    }
}
