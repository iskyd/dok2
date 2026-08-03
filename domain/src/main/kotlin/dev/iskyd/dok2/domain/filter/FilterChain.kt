package dev.iskyd.dok2.domain.filter

import dev.iskyd.dok2.domain.geo.Geo
import dev.iskyd.dok2.domain.model.GpsFix
import dev.iskyd.dok2.domain.model.PointState
import dev.iskyd.dok2.domain.model.TrackPoint

/**
 * Tuning constants for the three filter gates, exposed on the debug settings screen.
 *
 * These were tuned against real recorded fixtures — do not change them without showing the effect
 * across the whole fixture corpus (AGENTS.md).
 *
 * @property maxAccuracyM gate 1: reject fixes reporting horizontal accuracy worse than this.
 * @property maxSpeedMps gate 2: reject fixes implying more than this speed from the last accepted
 *   fix.
 * @property minDisplacementM gate 3: the lower bound of the displacement threshold.
 * @property displacementAccuracyFactor gate 3: the threshold scales as `accuracyM * this`, so a fix
 *   claiming 12 m accuracy must move 12 m.
 * @property maxDisplacementM gate 3: the upper clamp of the displacement threshold, so sustained
 *   bad reception cannot freeze the odometer permanently.
 */
data class FilterConfig(
    val maxAccuracyM: Double = 30.0,
    val maxSpeedMps: Double = 10.0,
    val minDisplacementM: Double = 4.0,
    val displacementAccuracyFactor: Double = 1.0,
    val maxDisplacementM: Double = 30.0,
) {
    companion object {
        /** The production configuration. */
        val DEFAULT = FilterConfig()
    }
}

/** Why a fix was rejected, for the debug log. [message] is what gets displayed. */
enum class RejectionReason(val message: String) {
    POOR_ACCURACY("horizontal accuracy exceeds the maximum"),
    IMPLAUSIBLE_SPEED("implied speed from the last accepted fix exceeds the maximum"),
}

/**
 * The outcome of running one fix through the filter chain.
 *
 * @property point the trackpoint to store. Accepted and stationary points are stored (pausing never
 *   discards points); rejected points are discarded by the caller but still carried for the debug
 *   log.
 * @property verdict one of ACCEPTED, STATIONARY, REJECTED.
 * @property distanceDeltaM the odometer contribution of this fix (0 for stationary and rejected
 *   fixes).
 * @property rejectionReason non-null iff [verdict] is REJECTED.
 */
sealed interface FilterResult {
    val point: TrackPoint
    val verdict: Verdict
    val distanceDeltaM: Double
    val rejectionReason: String?

    enum class Verdict {
        ACCEPTED,
        STATIONARY,
        REJECTED,
    }

    /** The fix advanced the anchor; [distanceDeltaM] was added to the odometer. */
    data class Accepted(override val point: TrackPoint, override val distanceDeltaM: Double) :
        FilterResult {
        override val verdict: Verdict = Verdict.ACCEPTED
        override val rejectionReason: String? = null
    }

    /** The fix was stored but did not advance the anchor, so nothing was accumulated. */
    data class Stationary(override val point: TrackPoint) : FilterResult {
        override val verdict: Verdict = Verdict.STATIONARY
        override val distanceDeltaM: Double = 0.0
        override val rejectionReason: String? = null
    }

    /**
     * The fix failed gate 1 or gate 2 and is discarded (glossary: rejected points are discarded).
     */
    data class Rejected(override val point: TrackPoint, val reason: RejectionReason) :
        FilterResult {
        override val verdict: Verdict = Verdict.REJECTED
        override val distanceDeltaM: Double = 0.0
        override val rejectionReason: String = reason.message
    }
}

/**
 * The aggregate outcome of a replay run, as used by the test harness.
 *
 * @property distanceM total accumulated distance in metres.
 * @property acceptedCount fixes that advanced the anchor.
 * @property stationaryCount fixes stored without accumulating.
 * @property rejectedCount fixes discarded by gate 1 or gate 2.
 */
data class ReplayResult(
    val distanceM: Double,
    val acceptedCount: Int,
    val stationaryCount: Int,
    val rejectedCount: Int,
) {
    val totalCount: Int
        get() = acceptedCount + stationaryCount + rejectedCount
}

/**
 * The three-gate fix filter from DOCUMENTATION.md §Filter chain.
 *
 * Gates, in order:
 * 1. accuracy — reject worse than [FilterConfig.maxAccuracyM];
 * 2. implausible speed — reject fixes implying more than [FilterConfig.maxSpeedMps] from the last
 *    accepted fix;
 * 3. minimum displacement, anchor-based.
 *
 * The chain holds an **anchor**: the last point that advanced the odometer. A new fix is compared
 * against the anchor, not against the previous fix. This is deliberate: with fix-to-fix comparison,
 * a slow ascent at 2 km/h (1.7 m per 3 s interval) would discard every point and the odometer would
 * read zero for the whole climb. With an anchor, slow movement accumulates correctly while standing
 * still accumulates nothing. Do not "fix" this — see AGENTS.md, "Things that look like bugs but are
 * not".
 *
 * The displacement threshold scales with reported accuracy `threshold = clamp(max(minDisplacementM,
 * accuracyM * displacementAccuracyFactor), .., maxDisplacementM)` and is clamped at
 * [FilterConfig.maxDisplacementM] so sustained bad reception cannot freeze the odometer
 * permanently.
 */
class FilterChain(private val config: FilterConfig = FilterConfig.DEFAULT) {

    private var anchor: GpsFix? = null
    private var distanceM: Double = 0.0
    private var acceptedCount: Int = 0
    private var stationaryCount: Int = 0
    private var rejectedCount: Int = 0

    /** Total accumulated distance in metres so far. */
    val accumulatedDistanceM: Double
        get() = distanceM

    /** The last fix that advanced the odometer, or null before the first accepted fix. */
    val anchorFix: GpsFix?
        get() = anchor

    /** Drops all state so the chain can be reused for a new track. */
    fun reset() {
        anchor = null
        distanceM = 0.0
        acceptedCount = 0
        stationaryCount = 0
        rejectedCount = 0
    }

    /**
     * Runs one fix through the three gates.
     *
     * @param fix the raw fix to filter.
     * @param pointState the recording state to tag the stored point with.
     */
    fun onFix(fix: GpsFix, pointState: PointState = PointState.RECORDING): FilterResult {
        if (fix.accuracyM > config.maxAccuracyM) {
            rejectedCount++
            return FilterResult.Rejected(
                point = toPoint(fix, pointState, accumulated = false),
                reason = RejectionReason.POOR_ACCURACY,
            )
        }

        val lastAccepted = anchor
        if (lastAccepted != null) {
            val dtSeconds = (fix.tMs - lastAccepted.tMs) / 1000.0
            if (dtSeconds > 0.0) {
                val impliedSpeed =
                    Geo.distanceM(
                        lastAccepted.latDeg,
                        lastAccepted.lonDeg,
                        fix.latDeg,
                        fix.lonDeg,
                    ) / dtSeconds
                if (impliedSpeed > config.maxSpeedMps) {
                    rejectedCount++
                    return FilterResult.Rejected(
                        point = toPoint(fix, pointState, accumulated = false),
                        reason = RejectionReason.IMPLAUSIBLE_SPEED,
                    )
                }
            }
        }

        val currentAnchor = anchor
        if (currentAnchor == null) {
            // First fix: it becomes the anchor and starts the odometer at zero.
            anchor = fix
            acceptedCount++
            return FilterResult.Accepted(
                point = toPoint(fix, pointState, accumulated = true),
                distanceDeltaM = 0.0,
            )
        }

        val threshold =
            (config.minDisplacementM.coerceAtLeast(
                    fix.accuracyM * config.displacementAccuracyFactor
                ))
                .coerceAtMost(config.maxDisplacementM)
        val displacement =
            Geo.distanceM(currentAnchor.latDeg, currentAnchor.lonDeg, fix.latDeg, fix.lonDeg)
        return if (displacement >= threshold) {
            distanceM += displacement
            anchor = fix
            acceptedCount++
            FilterResult.Accepted(
                point = toPoint(fix, pointState, accumulated = true),
                distanceDeltaM = displacement,
            )
        } else {
            stationaryCount++
            FilterResult.Stationary(point = toPoint(fix, pointState, accumulated = false))
        }
    }

    /**
     * Replays a list of fixes from a clean state and returns the aggregate result. This is the
     * harness the fixture tests use.
     */
    fun replay(fixes: List<GpsFix>): ReplayResult {
        reset()
        for (fix in fixes) {
            onFix(fix)
        }
        return ReplayResult(
            distanceM = distanceM,
            acceptedCount = acceptedCount,
            stationaryCount = stationaryCount,
            rejectedCount = rejectedCount,
        )
    }

    private fun toPoint(fix: GpsFix, state: PointState, accumulated: Boolean): TrackPoint =
        TrackPoint(
            tMs = fix.tMs,
            latDeg = fix.latDeg,
            lonDeg = fix.lonDeg,
            accuracyM = fix.accuracyM,
            altGnssM = fix.altGnssM,
            pressureHpa = fix.pressureHpa,
            speedMps = fix.speedMps,
            bearingDeg = fix.bearingDeg,
            state = state,
            accumulated = accumulated,
        )
}
