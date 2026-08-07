package dev.iskyd.dok2.domain.elevation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BarometerTest {

    @Test
    fun `barometric formula matches the documented values`() {
        assertThat(Barometer.altitudeFrom(1013.25, 1013.25)).isWithin(0.001).of(0.0)
        assertThat(Barometer.altitudeFrom(1000.0, 1013.25)).isWithin(0.01).of(110.901)
        assertThat(Barometer.altitudeFrom(900.0, 1013.25)).isWithin(0.01).of(988.647)
    }

    @Test
    fun `no pressure sample means no barometric altitude`() {
        val barometer = Barometer()
        barometer.start(0)
        assertThat(barometer.currentBarometricAltitudeM).isNull()
    }

    @Test
    fun `uncalibrated barometer falls back to 1013_25 hPa`() {
        val barometer = Barometer()
        barometer.start(0)
        barometer.onPressure(1_000, 1000.0)

        assertThat(barometer.calibrated).isFalse()
        assertThat(barometer.seaLevelHpa).isEqualTo(1013.25)
    }

    @Test
    fun `no usable gnss altitude leaves the barometer uncalibrated`() {
        val barometer = Barometer()
        barometer.start(0)
        // Only bad-accuracy GNSS altitudes, during and after the window.
        barometer.onGnssAltitude(1_000, 500.0, 20.0)
        barometer.onGnssAltitude(61_000, 500.0, 20.0)

        assertThat(barometer.calibrated).isFalse()
        assertThat(barometer.seaLevelHpa).isEqualTo(1013.25)
    }

    @Test
    fun `calibration solves p0 from the median of good gnss altitudes`() {
        val barometer = Barometer()
        barometer.start(0)
        barometer.onGnssAltitude(1_000, 99.0, 5.0)
        barometer.onGnssAltitude(4_000, 100.0, 5.0)
        // Poor-accuracy sample inside the window must be ignored.
        barometer.onGnssAltitude(5_500, 999.0, 20.0)
        barometer.onGnssAltitude(7_000, 100.5, 5.0)
        barometer.onGnssAltitude(10_000, 101.0, 5.0)
        barometer.onPressure(2_000, 1012.0)
        barometer.onPressure(5_000, 1012.0)
        // The first good sample after the 60 s window triggers calibration.
        barometer.onGnssAltitude(63_000, 100.4, 5.0)
        barometer.onPressure(63_000, 1012.0)

        assertThat(barometer.calibrated).isTrue()
        // median altitude (100 + 100.5) / 2 = 100.25; median pressure 1012.0.
        assertThat(barometer.seaLevelHpa).isWithin(0.01).of(1024.112)
        assertThat(barometer.currentBarometricAltitudeM).isWithin(1.0).of(100.25)
    }

    @Test
    fun `enough good gnss fixes calibrate before the window closes`() {
        val barometer = Barometer()
        barometer.start(0)
        barometer.onPressure(2_000, 1012.0)
        // The default early threshold is 8 fixes: the 8th good fix (t = 22 s, inside the 60 s
        // window) solves the baseline; no post-window sample is needed.
        for (i in 0L..7L) {
            barometer.onGnssAltitude(1_000 + i * 3_000, 100.0, 5.0)
        }

        assertThat(barometer.calibrated).isTrue()
        // p0 for median altitude 100.0 m at 1012.0 hPa.
        assertThat(barometer.seaLevelHpa).isWithin(0.01).of(1024.0816)
    }

    @Test
    fun `fewer than the early threshold good fixes wait for the window backstop`() {
        val barometer = Barometer()
        barometer.start(0)
        barometer.onPressure(2_000, 1012.0)
        // Four good fixes are below the 8-fix threshold: still uncalibrated inside the window.
        for (i in 0L..3L) {
            barometer.onGnssAltitude(1_000 + i * 3_000, 100.0, 5.0)
        }
        assertThat(barometer.calibrated).isFalse()
        // The first good fix after the 60 s window closes triggers calibration.
        barometer.onGnssAltitude(63_000, 100.4, 5.0)
        assertThat(barometer.calibrated).isTrue()
    }

    @Test
    fun `bad accuracy fixes do not count toward the early threshold`() {
        val barometer = Barometer()
        barometer.start(0)
        barometer.onPressure(2_000, 1012.0)
        // Eight fixes, but the last one is worse than 15 m: only 7 count, so no early exit.
        for (i in 0L..7L) {
            barometer.onGnssAltitude(1_000 + i * 3_000, 100.0, if (i == 7L) 20.0 else 5.0)
        }
        assertThat(barometer.calibrated).isFalse()
        barometer.onGnssAltitude(25_000, 100.0, 5.0)
        assertThat(barometer.calibrated).isTrue()
    }

    @Test
    fun `early threshold is configurable`() {
        val barometer = Barometer(Barometer.Config(earlyCalibrationMinFixes = 3))
        barometer.start(0)
        barometer.onPressure(2_000, 1012.0)
        barometer.onGnssAltitude(1_000, 100.0, 5.0)
        barometer.onGnssAltitude(4_000, 100.0, 5.0)
        assertThat(barometer.calibrated).isFalse()
        barometer.onGnssAltitude(7_000, 100.0, 5.0)
        assertThat(barometer.calibrated).isTrue()
    }

    @Test
    fun `drift correction nudges p0 toward the gnss median`() {
        val barometer = calibrateAround100Metres()

        val before = barometer.seaLevelHpa
        // Truth moves to 200 m; the median of the drift window reports it.
        barometer.onPressure(66_000, 1012.0)
        barometer.onGnssAltitude(66_000, 200.0, 5.0)
        val afterFirstStep = barometer.seaLevelHpa
        assertThat(afterFirstStep).isGreaterThan(before)
        // A ~100 m error is far beyond the clamp: exactly one max step is applied.
        assertThat(afterFirstStep - before).isWithin(1e-9).of(0.5)

        // Five minutes later the next correction step is applied, again clamped.
        barometer.onPressure(366_000, 1012.0)
        barometer.onGnssAltitude(366_000, 200.0, 5.0)
        assertThat(barometer.seaLevelHpa - afterFirstStep).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `median window rejects single-sample pressure spikes`() {
        val barometer = Barometer()
        barometer.start(0)
        // Steady 1000.0 hPa with one 8 hPa (≈67 m) spike in the middle. The median of the
        // 10-sample window must ignore it; the altitude stays at the 1000.0 baseline.
        for (t in 0L..9_000L step 1_000L) {
            barometer.onPressure(t, if (t == 5_000L) 992.0 else 1000.0)
        }
        assertThat(barometer.currentBarometricAltitudeM)
            .isWithin(1.0)
            .of(Barometer.altitudeFrom(1000.0, 1013.25))
    }

    @Test
    fun `spike leaves the median window once medianWindowSize samples have passed`() {
        val barometer = Barometer()
        barometer.start(0)
        // Fill the window with 1000.0, then switch to a genuine 990.0 (≈85 m up).
        for (t in 0L..9_000L step 1_000L) barometer.onPressure(t, 1000.0)
        barometer.onPressure(10_000L, 990.0)
        // One 990.0 among nine 1000.0: the median is still the old baseline.
        assertThat(barometer.currentBarometricAltitudeM)
            .isWithin(1.0)
            .of(Barometer.altitudeFrom(1000.0, 1013.25))
        // After a full window of 990.0 the median has moved to the new value.
        for (t in 11_000L..20_000L step 1_000L) barometer.onPressure(t, 990.0)
        assertThat(barometer.currentBarometricAltitudeM)
            .isWithin(1.0)
            .of(Barometer.altitudeFrom(990.0, 1013.25))
    }

    @Test
    fun `drift correction is clamped in both directions`() {
        val barometer = calibrateAround100Metres()
        val before = barometer.seaLevelHpa

        // Absurd GNSS altitude -> huge negative error, still clamped to -0.5 hPa.
        barometer.onPressure(66_000, 1012.0)
        barometer.onGnssAltitude(66_000, -5_000.0, 5.0)

        assertThat(before - barometer.seaLevelHpa).isWithin(1e-9).of(0.5)
    }

    private fun calibrateAround100Metres(): Barometer {
        val barometer = Barometer()
        barometer.start(0)
        barometer.onGnssAltitude(1_000, 99.0, 5.0)
        barometer.onGnssAltitude(4_000, 100.0, 5.0)
        barometer.onGnssAltitude(7_000, 100.5, 5.0)
        barometer.onGnssAltitude(10_000, 101.0, 5.0)
        barometer.onPressure(2_000, 1012.0)
        barometer.onGnssAltitude(63_000, 100.4, 5.0)
        barometer.onPressure(63_000, 1012.0)
        check(barometer.calibrated)
        return barometer
    }
}
