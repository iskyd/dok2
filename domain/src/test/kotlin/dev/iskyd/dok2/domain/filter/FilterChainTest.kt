package dev.iskyd.dok2.domain.filter

import com.google.common.truth.Truth.assertThat
import dev.iskyd.dok2.domain.TestFixtures.loadFixture
import dev.iskyd.dok2.domain.filter.FilterResult.Verdict.ACCEPTED
import dev.iskyd.dok2.domain.filter.FilterResult.Verdict.REJECTED
import dev.iskyd.dok2.domain.filter.FilterResult.Verdict.STATIONARY
import dev.iskyd.dok2.domain.geo.Geo
import dev.iskyd.dok2.domain.model.GpsFix
import dev.iskyd.dok2.domain.model.PointState
import org.junit.Test

class FilterChainTest {

    private fun fix(
        tMs: Long,
        latDeg: Double = 47.0,
        lonDeg: Double = 8.0,
        accuracyM: Double = 5.0,
        altGnssM: Double? = null,
    ) =
        GpsFix(
            tMs = tMs,
            latDeg = latDeg,
            lonDeg = lonDeg,
            accuracyM = accuracyM,
            altGnssM = altGnssM,
        )

    @Test
    fun `first fix becomes the anchor and is accepted with zero distance`() {
        val chain = FilterChain()
        val result = chain.onFix(fix(0))

        assertThat(result.verdict).isEqualTo(ACCEPTED)
        assertThat(result.distanceDeltaM).isEqualTo(0.0)
        assertThat(chain.accumulatedDistanceM).isEqualTo(0.0)
        assertThat(chain.anchorFix).isEqualTo(fix(0))
    }

    @Test
    fun `gate 1 rejects fixes with accuracy worse than 30 m`() {
        val chain = FilterChain()
        chain.onFix(fix(0))
        val result = chain.onFix(fix(3000, accuracyM = 30.1))

        assertThat(result.verdict).isEqualTo(REJECTED)
        assertThat(result.rejectionReason).contains("accuracy")
        // The anchor does not move on a rejected fix.
        assertThat(chain.anchorFix).isEqualTo(fix(0))
        assertThat(chain.accumulatedDistanceM).isEqualTo(0.0)
    }

    @Test
    fun `gate 1 accepts fixes at exactly the accuracy limit`() {
        val chain = FilterChain()
        chain.onFix(fix(0))
        val result = chain.onFix(fix(3000, latDeg = 47.0001, accuracyM = 30.0))

        assertThat(result.verdict).isNotEqualTo(REJECTED)
        assertThat(result.rejectionReason).isNull()
    }

    @Test
    fun `gate 2 rejects a teleport fix implying more than 10 m per second`() {
        val chain = FilterChain()
        chain.onFix(fix(0))
        // 90 m east in 3 s implies 30 m/s.
        val teleport = Geo.destinationPoint(47.0, 8.0, bearingDeg = 90.0, distanceMeters = 90.0)
        val result = chain.onFix(fix(3000, latDeg = teleport.latDeg, lonDeg = teleport.lonDeg))

        assertThat(result.verdict).isEqualTo(REJECTED)
        assertThat(result.rejectionReason).contains("speed")
        assertThat(chain.accumulatedDistanceM).isEqualTo(0.0)
    }

    @Test
    fun `gate 2 allows walking speed`() {
        val chain = FilterChain()
        chain.onFix(fix(0))
        val walk = Geo.destinationPoint(47.0, 8.0, bearingDeg = 90.0, distanceMeters = 6.0)
        val result = chain.onFix(fix(3000, latDeg = walk.latDeg, lonDeg = walk.lonDeg))

        assertThat(result.verdict).isNotEqualTo(REJECTED)
        assertThat(chain.accumulatedDistanceM).isWithin(0.01).of(6.0)
    }

    @Test
    fun `stationary fix is stored without accumulating distance`() {
        val chain = FilterChain()
        chain.onFix(fix(0))
        val result = chain.onFix(fix(3000, latDeg = 47.0000005))

        assertThat(result.verdict).isEqualTo(STATIONARY)
        assertThat(result.distanceDeltaM).isEqualTo(0.0)
        assertThat(result.point.accumulated).isFalse()
        assertThat(result.point.state).isEqualTo(PointState.RECORDING)
        assertThat(chain.accumulatedDistanceM).isEqualTo(0.0)
        assertThat(chain.anchorFix).isEqualTo(fix(0))
    }

    @Test
    fun `threshold scales with reported accuracy`() {
        // accuracy 12 m -> threshold = max(4, 12*1.0) = 12 m.
        val chain = FilterChain()
        chain.onFix(fix(0, accuracyM = 12.0))

        val tenMetres = Geo.destinationPoint(47.0, 8.0, bearingDeg = 90.0, distanceMeters = 10.0)
        val under =
            chain.onFix(
                fix(3000, latDeg = tenMetres.latDeg, lonDeg = tenMetres.lonDeg, accuracyM = 12.0)
            )
        assertThat(under.verdict).isEqualTo(STATIONARY)

        // The anchor has not moved, so a 15 m fix is still compared against the origin.
        val fifteenMetres =
            Geo.destinationPoint(47.0, 8.0, bearingDeg = 90.0, distanceMeters = 15.0)
        val over =
            chain.onFix(
                fix(
                    6000,
                    latDeg = fifteenMetres.latDeg,
                    lonDeg = fifteenMetres.lonDeg,
                    accuracyM = 12.0,
                )
            )
        assertThat(over.verdict).isEqualTo(ACCEPTED)
        assertThat(over.distanceDeltaM).isWithin(0.01).of(15.0)
    }

    @Test
    fun `points are tagged with the given recording state`() {
        val chain = FilterChain()
        val result = chain.onFix(fix(0), pointState = PointState.AUTO_PAUSED)
        assertThat(result.point.state).isEqualTo(PointState.AUTO_PAUSED)
    }

    @Test
    fun `raw sensor values flow through the point untouched`() {
        val chain = FilterChain()
        val result =
            chain.onFix(
                GpsFix(
                    tMs = 0,
                    latDeg = 47.0,
                    lonDeg = 8.0,
                    accuracyM = 5.0,
                    altGnssM = 1234.5,
                    pressureHpa = 950.25,
                )
            )
        assertThat(result.point.altGnssM).isEqualTo(1234.5)
        assertThat(result.point.pressureHpa).isEqualTo(950.25)
    }

    @Test
    fun `replay resets the chain and reports counts`() {
        val chain = FilterChain()
        chain.onFix(fix(0))
        val result = chain.replay(listOf(fix(0), fix(3000), fix(6000, accuracyM = 40.0)))

        assertThat(result.acceptedCount).isEqualTo(1)
        assertThat(result.stationaryCount).isEqualTo(1)
        assertThat(result.rejectedCount).isEqualTo(1)
        assertThat(result.totalCount).isEqualTo(3)
        assertThat(result.distanceM).isEqualTo(0.0)
    }

    @Test
    fun `slow ascent at 2 km per hour accumulates distance`() {
        // The fixture walks north at 2 km/h: 1.67 m per 3 s interval, with accuracy 5 m
        // giving a displacement threshold of 5 m. Fix-to-fix comparison would discard
        // every point and the odometer would read zero for the whole climb; the
        // anchor-based comparison accumulates the full ~998 m path.
        val result = FilterChain().replay(loadFixture("slow-ascent.jsonl"))

        assertThat(result.distanceM).isWithin(50.0).of(998.0)
        assertThat(result.rejectedCount).isEqualTo(0)
    }

    @Test
    fun `lunch stop does not accumulate phantom distance`() {
        // 500 m walk out, a 45-minute stop with sub-threshold jitter, 500 m walk back.
        // The stop contributes ~nothing: the accumulated distance is the ~994 m of the
        // real walking, not 994 m plus kilometres of phantom drift.
        val result = FilterChain().replay(loadFixture("lunch-stop.jsonl"))

        assertThat(result.distanceM).isWithin(50.0).of(993.6)
        assertThat(result.distanceM).isLessThan(1000.0)
    }

    @Test
    fun `teleport fix is rejected by the speed gate`() {
        val result = FilterChain().replay(loadFixture("teleport.jsonl"))

        // The 3 km instantaneous jump and the fixes that follow it while the implied
        // speed stays above 10 m/s are all rejected.
        assertThat(result.rejectedCount).isAtLeast(1)
        // The odometer reflects the physically-real displacement, not a phantom jump.
        assertThat(result.distanceM).isWithin(50.0).of(3936.0)
    }

    @Test
    fun `reset clears anchor and distance`() {
        val chain = FilterChain()
        val walk = Geo.destinationPoint(47.0, 8.0, bearingDeg = 90.0, distanceMeters = 10.0)
        chain.onFix(fix(0))
        chain.onFix(fix(3000, latDeg = walk.latDeg, lonDeg = walk.lonDeg))
        assertThat(chain.accumulatedDistanceM).isGreaterThan(0.0)

        chain.reset()
        assertThat(chain.accumulatedDistanceM).isEqualTo(0.0)
        assertThat(chain.anchorFix).isNull()
    }
}
