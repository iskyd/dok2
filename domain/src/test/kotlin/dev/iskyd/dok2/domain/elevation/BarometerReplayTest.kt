package dev.iskyd.dok2.domain.elevation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression replay of track 18 ("run", 2026-08-04, Onore -> Clusone, 8.2 km): the device recorded
 * gain_baro_m = 1314.98 / loss_baro_m = 1336.70 against a true ~107 m gain (GNSS truth below).
 *
 * Root cause (2026-08): the accumulator was fed a fresh altitude per fix computed from a single raw
 * instantaneous pressure sample. Walking-induced spikes of ±0.3–1.9 hPa (2.5–16 m) regularly
 * exceeded the 5 m hysteresis, booking hundreds of phantom gain/loss events. The fix: the median of
 * the last 10 pressure samples replaces the raw sample in altitude computations.
 *
 * These tests lock that behaviour on real device data. Fixture: track18_points.csv — `t_ms,
 * accuracy_m, alt_gnss_m, pressure_hpa, state` per fix (no coordinates; privacy-safe).
 */
class BarometerReplayTest {

    private data class Sample(
        val tMs: Long,
        val accuracyM: Double,
        val altGnssM: Double,
        val pressureHpa: Double,
        val state: Int,
    )

    private fun loadSamples(): List<Sample> {
        val resource = "/fixtures/track18_points.csv"
        val stream =
            checkNotNull(javaClass.getResourceAsStream(resource)) { "fixture not found: $resource" }
        return stream.bufferedReader().useLines { lines ->
            lines
                .filter { it.isNotBlank() }
                .map { line ->
                    val parts = line.split(',')
                    Sample(
                        tMs = parts[0].toLong(),
                        accuracyM = parts[1].toDouble(),
                        altGnssM = parts[2].toDouble(),
                        pressureHpa = parts[3].toDouble(),
                        state = parts[4].toInt(),
                    )
                }
                .toList()
        }
    }

    /** Mirrors the barometer feed in RecordingService.handleFix. */
    private class Feed(private val barometer: Barometer, private val withGate: Boolean = true) {
        private val stats = ElevationStats()
        private val gate = ElevationRateGate()
        private var wasCalibrated = false

        val gainM: Double
            get() = stats.gainM

        val lossM: Double
            get() = stats.lossM

        fun onFix(sample: Sample) {
            barometer.onPressure(sample.tMs, sample.pressureHpa)
            barometer.onGnssAltitude(sample.tMs, sample.altGnssM, sample.accuracyM)
            if (barometer.calibrated && !wasCalibrated) {
                barometer.currentBarometricAltitudeM?.let {
                    gate.reseed(sample.tMs, it)
                    stats.reseed(it)
                }
            }
            wasCalibrated = barometer.calibrated
            if (sample.state == 0) {
                barometer.currentBarometricAltitudeM?.let {
                    stats.add(if (withGate) gate.accept(sample.tMs, it) else it)
                }
            }
        }
    }

    @Test
    fun `fixed pipeline reports the true elevation on the real hike`() {
        val feed = Feed(Barometer())
        for (sample in loadSamples()) feed.onFix(sample)
        println(
            "FIXED pipeline: gain=${"%.1f".format(feed.gainM)} loss=${"%.1f".format(feed.lossM)}"
        )
        assertThat(feed.gainM).isGreaterThan(50.0)
        assertThat(feed.gainM).isLessThan(150.0)
        assertThat(feed.lossM).isGreaterThan(80.0)
        assertThat(feed.lossM).isLessThan(180.0)
    }

    @Test
    fun `single sample feed reproduces the phantom gain the fix removes`() {
        // medianWindowSize = 1 disables smoothing: the regression signature of the old bug. The
        // rate gate is bypassed so this test isolates the median's contribution.
        val feed = Feed(Barometer(Barometer.Config(medianWindowSize = 1)), withGate = false)
        for (sample in loadSamples()) feed.onFix(sample)
        println("RAW feed: gain=${"%.1f".format(feed.gainM)} loss=${"%.1f".format(feed.lossM)}")
        assertThat(feed.gainM).isGreaterThan(1_000.0)
        assertThat(feed.lossM).isGreaterThan(1_000.0)
    }

    @Test
    fun `rate gate tames the phantom gain of a raw single sample feed`() {
        val rawFeed = Feed(Barometer(Barometer.Config(medianWindowSize = 1)), withGate = false)
        val gatedFeed = Feed(Barometer(Barometer.Config(medianWindowSize = 1)), withGate = true)
        for (sample in loadSamples()) {
            rawFeed.onFix(sample)
            gatedFeed.onFix(sample)
        }
        println(
            "RAW+GATE pipeline: gain=${"%.1f".format(gatedFeed.gainM)} " +
                "loss=${"%.1f".format(gatedFeed.lossM)}"
        )
        assertThat(gatedFeed.gainM).isLessThan(rawFeed.gainM / 2.0)
        assertThat(gatedFeed.lossM).isLessThan(rawFeed.lossM / 2.0)
        assertThat(gatedFeed.gainM).isGreaterThan(50.0)
        assertThat(gatedFeed.lossM).isGreaterThan(80.0)
    }

    @Test
    fun `gnss truth on the real hike is roughly a hundred metres`() {
        val stats = ElevationStats()
        for (sample in loadSamples()) stats.add(sample.altGnssM)
        println("GNSS truth: gain=${"%.1f".format(stats.gainM)} loss=${"%.1f".format(stats.lossM)}")
        assertThat(stats.gainM).isGreaterThan(80.0)
        assertThat(stats.gainM).isLessThan(130.0)
    }
}
