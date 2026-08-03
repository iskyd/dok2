package dev.iskyd.dok2.domain.elevation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ElevationStatsTest {

    @Test
    fun `first sample establishes the reference without accumulating`() {
        val stats = ElevationStats()
        stats.add(100.0)

        assertThat(stats.gainM).isEqualTo(0.0)
        assertThat(stats.lossM).isEqualTo(0.0)
    }

    @Test
    fun `flat noise produces no phantom gain`() {
        val stats = ElevationStats()
        stats.add(100.0)
        for (i in 0 until 100) {
            stats.add(100.0 + (i % 5) - 2)
        }

        assertThat(stats.gainM).isEqualTo(0.0)
        assertThat(stats.lossM).isEqualTo(0.0)
    }

    @Test
    fun `movement below the hysteresis is ignored`() {
        val stats = ElevationStats()
        stats.add(100.0)
        stats.add(104.0)
        stats.add(100.0)

        assertThat(stats.gainM).isEqualTo(0.0)
        assertThat(stats.lossM).isEqualTo(0.0)
    }

    @Test
    fun `sustained climb accumulates gain`() {
        val stats = ElevationStats()
        stats.add(100.0)
        stats.add(106.0) // +6 > 5 -> gain 6, reference moves to 106
        stats.add(110.0) // +4 from the reference -> ignored
        stats.add(112.0) // +6 from the reference -> gain 6

        assertThat(stats.gainM).isWithin(1e-9).of(12.0)
        assertThat(stats.lossM).isEqualTo(0.0)
        assertThat(stats.referenceM).isEqualTo(112.0)
    }

    @Test
    fun `sustained descent accumulates loss`() {
        val stats = ElevationStats()
        stats.add(1000.0)
        stats.add(994.0) // -6 -> loss 6, reference moves to 994
        stats.add(990.0) // -4 from the reference -> ignored
        stats.add(984.0) // -10 from the reference -> loss 10

        assertThat(stats.lossM).isWithin(1e-9).of(16.0)
        assertThat(stats.gainM).isEqualTo(0.0)
    }

    @Test
    fun `climb after descent keeps the figures separate`() {
        val stats = ElevationStats()
        stats.add(500.0)
        stats.add(493.0) // loss 7
        stats.add(507.0) // +14 from reference -> gain 14

        assertThat(stats.lossM).isWithin(1e-9).of(7.0)
        assertThat(stats.gainM).isWithin(1e-9).of(14.0)
    }

    @Test
    fun `reseed re-anchors without recording the calibration jump`() {
        val stats = ElevationStats()
        stats.add(100.0)
        stats.add(110.0) // +10 -> gain 10
        stats.reseed(30.0) // barometer calibration snaps the baseline by 80 m
        stats.add(45.0) // +15 from the reseeded reference -> gain 15

        assertThat(stats.gainM).isWithin(1e-9).of(25.0)
        assertThat(stats.lossM).isEqualTo(0.0)
        assertThat(stats.referenceM).isEqualTo(45.0)
    }

    @Test
    fun `reset clears the accumulator`() {
        val stats = ElevationStats()
        stats.add(100.0)
        stats.add(110.0)
        stats.reset()

        assertThat(stats.gainM).isEqualTo(0.0)
        assertThat(stats.lossM).isEqualTo(0.0)
        assertThat(stats.referenceM).isNull()
    }
}
