package dev.iskyd.dok2.domain.state

import com.google.common.truth.Truth.assertThat
import dev.iskyd.dok2.domain.model.RecordingState
import org.junit.Test

class RecordingStateMachineTest {

    @Test
    fun `start transitions idle to recording and emits a pause event`() {
        val machine = RecordingStateMachine()
        val events = mutableListOf<PauseEvent>()
        machine.addListener { events += it }

        val transitioned = machine.start(1_000)

        assertThat(transitioned).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.Recording)
        assertThat(machine.recordingStartedAtMs).isEqualTo(1_000)
        assertThat(events)
            .containsExactly(PauseEvent(tMs = 1_000, newState = RecordingState.Recording))
    }

    @Test
    fun `start is a no-op while already recording`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        assertThat(machine.start(10_000)).isFalse()
        assertThat(machine.recordingStartedAtMs).isEqualTo(0)
    }

    @Test
    fun `user pause from recording moves to manual paused`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        assertThat(machine.userPause(5_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.ManualPaused)
    }

    @Test
    fun `user pause from auto paused moves to manual paused`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.autoPauseTimeout(60_000)
        assertThat(machine.state).isEqualTo(RecordingState.AutoPaused)
        assertThat(machine.userPause(90_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.ManualPaused)
    }

    @Test
    fun `user pause while already manually paused is a no-op`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.userPause(5_000)
        assertThat(machine.userPause(10_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.ManualPaused)
    }

    @Test
    fun `user resume from manual paused moves back to recording`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.userPause(5_000)
        assertThat(machine.userResume(10_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.Recording)
    }

    @Test
    fun `user resume while recording is a no-op`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        assertThat(machine.userResume(5_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.Recording)
    }

    @Test
    fun `auto pause triggers after 60 s with no accepted displacement`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.fixAccepted(3_000)

        assertThat(machine.autoPauseTimeout(63_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.AutoPaused)
    }

    @Test
    fun `auto pause does not trigger before 60 s have elapsed`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.fixAccepted(3_000)

        assertThat(machine.autoPauseTimeout(62_999)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.Recording)
    }

    @Test
    fun `auto pause uses the recording start when no fix was ever accepted`() {
        val machine = RecordingStateMachine()
        machine.start(0)

        assertThat(machine.autoPauseTimeout(60_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.AutoPaused)
    }

    @Test
    fun `accepted displacement while auto paused auto resumes to recording`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.fixAccepted(3_000)
        machine.autoPauseTimeout(63_000)
        assertThat(machine.state).isEqualTo(RecordingState.AutoPaused)

        assertThat(machine.fixAccepted(66_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.Recording)
    }

    @Test
    fun `movement does not auto resume a manual pause`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.userPause(5_000)

        assertThat(machine.fixAccepted(30_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.ManualPaused)
    }

    @Test
    fun `auto pause is suspended while manually paused`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.fixAccepted(3_000)
        machine.userPause(5_000)

        assertThat(machine.autoPauseTimeout(1_000_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.ManualPaused)
    }

    @Test
    fun `stop from recording finalises to idle`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        assertThat(machine.stop(10_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.Idle)
    }

    @Test
    fun `stop from a paused state finalises to idle`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.userPause(5_000)
        assertThat(machine.stop(10_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.Idle)
    }

    @Test
    fun `stop while idle is a no-op`() {
        val machine = RecordingStateMachine()
        assertThat(machine.stop(10_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.Idle)
    }

    @Test
    fun `gnss interval is 3 s while recording`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        assertThat(machine.gnssIntervalMsAt(5_000)).isEqualTo(3_000)
    }

    @Test
    fun `gnss interval drops to the 30 s trickle after 2 minutes of manual pause`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.userPause(10_000)

        assertThat(machine.gnssIntervalMsAt(10_000 + 119_999)).isEqualTo(3_000)
        assertThat(machine.gnssIntervalMsAt(10_000 + 120_000)).isEqualTo(30_000)
        assertThat(machine.gnssIntervalMsAt(1_000_000)).isEqualTo(30_000)
    }

    @Test
    fun `trickle interval is restored to 3 s on resume`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.userPause(10_000)
        machine.userResume(200_000)

        assertThat(machine.gnssIntervalMsAt(1_000_000)).isEqualTo(3_000)
    }

    @Test
    fun `moving time accumulates only while recording`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.fixAccepted(3_000)
        machine.autoPauseTimeout(63_000)
        machine.fixAccepted(66_000)
        machine.userPause(96_000)
        machine.fixAccepted(126_000)
        machine.userResume(156_000)
        machine.stop(186_000)

        // 0-63 s recording (3 s + 60 s), 66-96 s recording, 156-186 s recording.
        assertThat(machine.movingTimeMs).isEqualTo(123_000)
    }

    @Test
    fun `elapsed time accumulates in all non-idle states`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        machine.fixAccepted(3_000)
        machine.autoPauseTimeout(63_000)
        machine.fixAccepted(66_000)
        machine.userPause(96_000)
        machine.fixAccepted(126_000)
        machine.userResume(156_000)
        machine.stop(186_000)

        // The whole session from 0 to 186 s, including auto- and manual-paused spans.
        assertThat(machine.elapsedTimeMs).isEqualTo(186_000)
    }

    @Test
    fun `segment start follows the current state segment`() {
        val machine = RecordingStateMachine()
        assertThat(machine.segmentStartMs).isNull()

        machine.start(0L)
        assertThat(machine.segmentStartMs).isEqualTo(0L)
        machine.fixAccepted(3_000L)
        assertThat(machine.segmentStartMs).isEqualTo(3_000L)
        machine.userPause(5_000L)
        assertThat(machine.segmentStartMs).isEqualTo(5_000L)
        machine.userResume(10_000L)
        assertThat(machine.segmentStartMs).isEqualTo(10_000L)
        machine.stop(12_000L)
        assertThat(machine.segmentStartMs).isNull()
    }

    @Test
    fun `every transition emits a pause event in order`() {
        val machine = RecordingStateMachine()
        val events = mutableListOf<PauseEvent>()
        machine.addListener { events += it }

        machine.start(0)
        machine.fixAccepted(3_000)
        machine.autoPauseTimeout(63_000)
        machine.fixAccepted(66_000)
        machine.userPause(96_000)
        machine.userResume(156_000)
        machine.stop(186_000)

        assertThat(events)
            .containsExactly(
                PauseEvent(0, RecordingState.Recording),
                PauseEvent(63_000, RecordingState.AutoPaused),
                PauseEvent(66_000, RecordingState.Recording),
                PauseEvent(96_000, RecordingState.ManualPaused),
                PauseEvent(156_000, RecordingState.Recording),
                PauseEvent(186_000, RecordingState.Idle),
            )
            .inOrder()
    }

    @Test
    fun `start calibration transitions idle to calibrating`() {
        val machine = RecordingStateMachine()
        val events = mutableListOf<PauseEvent>()
        machine.addListener { events += it }

        val transitioned = machine.startCalibration(1_000)

        assertThat(transitioned).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.Calibrating)
        assertThat(machine.recordingStartedAtMs).isNull()
        assertThat(events)
            .containsExactly(PauseEvent(tMs = 1_000, newState = RecordingState.Calibrating))
    }

    @Test
    fun `start calibration is a no-op unless idle`() {
        val machine = RecordingStateMachine()
        machine.start(0)
        assertThat(machine.startCalibration(10_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.Recording)
    }

    @Test
    fun `begin recording from calibrating moves to recording and starts the clock`() {
        val machine = RecordingStateMachine()
        machine.startCalibration(0)
        val events = mutableListOf<PauseEvent>()
        machine.addListener { events += it }

        assertThat(machine.beginRecording(60_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.Recording)
        assertThat(machine.recordingStartedAtMs).isEqualTo(60_000)
        assertThat(events)
            .containsExactly(PauseEvent(tMs = 60_000, newState = RecordingState.Recording))
    }

    @Test
    fun `begin recording is a no-op outside calibrating`() {
        val machine = RecordingStateMachine()
        assertThat(machine.beginRecording(10_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.Idle)

        machine.start(20_000)
        assertThat(machine.beginRecording(30_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.Recording)
    }

    @Test
    fun `elapsed and moving time do not accumulate during calibrating`() {
        val machine = RecordingStateMachine()
        machine.startCalibration(0)
        machine.beginRecording(60_000)
        machine.fixAccepted(63_000)
        machine.stop(66_000)

        // Only the 3 s recording spans count; the 60 s calibration window is not elapsed time.
        assertThat(machine.elapsedTimeMs).isEqualTo(6_000)
        assertThat(machine.movingTimeMs).isEqualTo(6_000)
    }

    @Test
    fun `stop during calibrating finalises to idle without accumulating time`() {
        val machine = RecordingStateMachine()
        machine.startCalibration(0)

        assertThat(machine.stop(30_000)).isTrue()
        assertThat(machine.state).isEqualTo(RecordingState.Idle)
        assertThat(machine.elapsedTimeMs).isEqualTo(0)
        assertThat(machine.movingTimeMs).isEqualTo(0)
        assertThat(machine.recordingStartedAtMs).isNull()
    }

    @Test
    fun `accepted displacement during calibrating is a no-op`() {
        val machine = RecordingStateMachine()
        machine.startCalibration(0)
        assertThat(machine.fixAccepted(3_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.Calibrating)
    }

    @Test
    fun `user pause during calibrating is a no-op`() {
        val machine = RecordingStateMachine()
        machine.startCalibration(0)
        assertThat(machine.userPause(5_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.Calibrating)
    }

    @Test
    fun `user resume during calibrating is a no-op`() {
        val machine = RecordingStateMachine()
        machine.startCalibration(0)
        assertThat(machine.userResume(5_000)).isFalse()
        assertThat(machine.state).isEqualTo(RecordingState.Calibrating)
    }
}
