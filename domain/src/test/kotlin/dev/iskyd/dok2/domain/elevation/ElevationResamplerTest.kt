package dev.iskyd.dok2.domain.elevation

import com.google.common.truth.Truth.assertThat
import dev.iskyd.dok2.domain.TestFixtures.loadFixture
import dev.iskyd.dok2.domain.geo.Geo
import dev.iskyd.dok2.domain.model.PointState
import dev.iskyd.dok2.domain.model.TrackPoint
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

/**
 * Tests for [ElevationResampler], the ~15 m sub-sampling of the DEM gain/loss feed at save time
 * (DOCUMENTATION.md, Elevation — Final).
 *
 * The sub-sample-undulation test is synthetic: a 17.5 m-wavelength ±6 m altitude wiggle (the shape
 * a coarse SRTM grid produces from sub-grid GNSS noise on a slope) fed to the accumulator at full
 * resolution double-books through the 5 m hysteresis; the 15 m-resampled feed only sees the real
 * climb. The slow-ascent fixture replay guards the other direction: resampling must not
 * under-report a genuine monotonic climb.
 */
class ElevationResamplerTest {

    private fun point(latDeg: Double, lonDeg: Double, altGnssM: Double? = null) =
        TrackPoint(
            tMs = 0L,
            latDeg = latDeg,
            lonDeg = lonDeg,
            accuracyM = 5.0,
            altGnssM = altGnssM,
            pressureHpa = null,
            speedMps = null,
            bearingDeg = null,
            state = PointState.RECORDING,
            accumulated = true,
        )

    private fun distanceM(a: TrackPoint, b: TrackPoint) =
        Geo.distanceM(a.latDeg, a.lonDeg, b.latDeg, b.lonDeg)

    @Test
    fun `keeps first and last point with at least minSpacingM between interior points`() {
        // 0.0001 deg of latitude is ~11.13 m, so roughly every second point is kept.
        val points = (0 until 200).map { i -> point(47.0 + i * 0.0001, 8.0) }

        val kept = ElevationResampler.indices(points)

        assertThat(kept.first()).isEqualTo(0)
        assertThat(kept.last()).isEqualTo(points.lastIndex)
        for (i in 0 until kept.size - 1) {
            val a = points[kept[i]]
            val b = points[kept[i + 1]]
            if (kept[i + 1] != points.lastIndex) {
                // Interior hops are at least the requested spacing; only the force-added
                // tail point may sit closer to its predecessor.
                assertThat(distanceM(a, b)).isAtLeast(15.0)
            }
        }
    }

    @Test
    fun `keeps every point when they are already spaced beyond minSpacingM`() {
        // 0.00018 deg of latitude is ~20 m.
        val points = (0 until 100).map { i -> point(47.0 + i * 0.00018, 8.0) }

        val kept = ElevationResampler.indices(points)

        assertThat(kept).containsExactlyElementsIn(points.indices).inOrder()
    }

    @Test
    fun `empty and single-point paths pass through unchanged`() {
        assertThat(ElevationResampler.indices(emptyList())).isEmpty()
        assertThat(ElevationResampler.indices(listOf(point(47.0, 8.0)))).containsExactly(0)
    }

    @Test
    fun `slow ascent fixture keeps its climb after resampling`() {
        // 600 fixes, ~1.67 m apart, monotonic climb of ~100 m over ~1 km (1000 -> ~1100 m).
        val points =
            loadFixture("slow-ascent.jsonl").map { fix ->
                point(fix.latDeg, fix.lonDeg, fix.altGnssM)
            }
        val full = ElevationStats()
        points.forEach { full.add(it.altGnssM!!) }
        val resampled = ElevationStats()
        ElevationResampler.indices(points).forEach { resampled.add(points[it].altGnssM!!) }

        // The full-resolution feed sees the whole climb...
        assertThat(full.gainM).isAtLeast(90.0)
        assertThat(full.lossM).isAtMost(10.0)
        // ...and resampling must not throw it away: the reported climb stays within one
        // hysteresis step of the full feed, and the loss stays negligible.
        assertThat(resampled.gainM).isAtLeast(full.gainM - 15.0)
        assertThat(resampled.gainM).isAtLeast(85.0)
        assertThat(resampled.lossM).isAtMost(10.0)
    }

    @Test
    fun `sub-sample undulation no longer double-books through the hysteresis`() {
        // A 3.5 m-spaced path along the meridian-equivalent at 47 N, carrying a real 0.75 m/point
        // climb plus a 5-point (17.5 m) wavelength, ±6 m altitude wiggle — the signature of a
        // coarse DEM undulating beneath sub-grid GNSS noise. 5-point hops (17.5 m) land on the
        // same wiggle phase, so the resampled feed sees only the real climb.
        // 3.5 m in longitude at 47 N (metres per degree of longitude there).
        val lonStep = 3.5 / Geo.distanceM(47.0, 0.0, 47.0, 1.0)
        val points =
            (0 until 400).map { i ->
                point(47.0, i * lonStep, altGnssM = 1000.0 + 0.75 * i + 6.0 * sin(2 * PI * i / 5))
            }

        val full = ElevationStats()
        points.forEach { full.add(it.altGnssM!!) }
        val resampled = ElevationStats()
        ElevationResampler.indices(points).forEach { resampled.add(points[it].altGnssM!!) }

        // Full resolution: the wiggle trips the hysteresis in both directions -> phantom
        // gain and loss. 15 m resampling: only the ~300 m real climb is reported.
        assertThat(full.gainM).isGreaterThan(2 * resampled.gainM)
        assertThat(full.lossM).isGreaterThan(100.0)
        assertThat(resampled.gainM).isAtLeast(290.0)
        assertThat(resampled.gainM).isAtMost(310.0)
        assertThat(resampled.lossM).isAtMost(10.0)
    }
}
