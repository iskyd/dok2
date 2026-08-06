package dev.iskyd.dok2.domain.elevation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ElevationRateGateTest {

    private val gate = ElevationRateGate()
    private val nominalFixMs = 3_000L

    private fun feed(tMs: Long, altM: Double): Double = gate.accept(tMs, altM)

    @Test
    fun `first sample establishes the reference and passes through`() {
        assertThat(feed(0L, 500.0)).isEqualTo(500.0)
    }

    @Test
    fun `slow ascent passes through unclamped`() {
        feed(0L, 500.0)
        assertThat(feed(nominalFixMs, 503.0)).isEqualTo(503.0) // 1 m/s
    }

    @Test
    fun `fast descent passes through when within the looser descent bound`() {
        feed(0L, 500.0)
        // 2 m/s downhill — genuine trail-running descent, allowed by the 2.5 m/s bound.
        assertThat(feed(nominalFixMs, 494.0)).isEqualTo(494.0)
    }

    @Test
    fun `spike ascent clamps to the ascent bound`() {
        feed(0L, 500.0)
        // 5 m/s up is impossible for a hiker; clamped to 1.5 m/s * 3 s = 4.5 m.
        assertThat(feed(nominalFixMs, 515.0)).isWithin(1e-9).of(504.5)
    }

    @Test
    fun `spike descent clamps to the descent bound`() {
        feed(0L, 500.0)
        // 4 m/s down; clamped to 2.5 m/s * 3 s = 7.5 m.
        assertThat(feed(nominalFixMs, 488.0)).isWithin(1e-9).of(492.5)
    }

    @Test
    fun `ascent bound is configurable and independent of the descent bound`() {
        val gated =
            ElevationRateGate(ElevationRateGate.Config(ascentMaxMps = 1.0, descentMaxMps = 2.5))
        gated.accept(0L, 500.0)
        // 2 m/s up clamps to the configured 1.0 m/s * 3 s = 3 m, not the 2.5 m/s descent bound.
        assertThat(gated.accept(nominalFixMs, 506.0)).isWithin(1e-9).of(503.0)
    }

    @Test
    fun `allowed step scales with the fix interval`() {
        feed(0L, 500.0)
        // A 6 s gap (dropped fix) allows 9 m at the same 1.5 m/s speed bound.
        assertThat(feed(6_000L, 509.0)).isWithin(1e-9).of(509.0)
        // And clamps a 20 m step across that gap to 9 m.
        feed(12_000L, 509.0)
        assertThat(feed(18_000L, 529.0)).isWithin(1e-9).of(518.0)
    }

    @Test
    fun `zero interval cannot produce a step`() {
        feed(0L, 500.0)
        assertThat(feed(0L, 515.0)).isWithin(1e-9).of(500.0)
    }

    @Test
    fun `reseed re-anchors without clamping the jump`() {
        feed(0L, 500.0)
        feed(nominalFixMs, 520.0) // clamped to 504.5, then re-anchored:
        gate.reseed(6_000L, 480.0)
        assertThat(feed(9_000L, 484.0)).isEqualTo(484.0) // 1.33 m/s up from the new anchor
    }

    @Test
    fun `reset clears the reference`() {
        feed(0L, 500.0)
        gate.reset()
        assertThat(feed(0L, 42.0)).isEqualTo(42.0)
    }

    @Test
    fun `clamped spike does not accumulate into later samples`() {
        feed(0L, 500.0)
        feed(nominalFixMs, 515.0) // 15 m spike -> 504.5
        // Spike reverts: the return is also bounded, so the signal converges back.
        assertThat(feed(2 * nominalFixMs, 500.0)).isWithin(1e-9).of(500.0)
    }

    @Test
    fun `configure swaps the bounds for subsequent steps only`() {
        feed(0L, 500.0)
        assertThat(feed(nominalFixMs, 515.0)).isWithin(1e-9).of(504.5) // default 1.5 m/s

        gate.configure(ElevationRateGate.Config(ascentMaxMps = 3.0, descentMaxMps = 3.0))

        // 2.5 m/s up from the anchor: clamped before, now within the looser bound.
        assertThat(feed(2 * nominalFixMs, 512.0)).isWithin(1e-9).of(512.0)
    }
}
