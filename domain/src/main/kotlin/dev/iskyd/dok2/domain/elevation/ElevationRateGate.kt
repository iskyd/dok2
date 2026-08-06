package dev.iskyd.dok2.domain.elevation

/**
 * Bounds the per-fix altitude change fed to [ElevationStats], per DOCUMENTATION.md §Elevation —
 * Live — rate gate.
 *
 * The barometric median window kills sub-second pressure spikes, but a ≥1.2 s event (gust of wind,
 * blocked sensor port) still moves the medianed altitude. A hiker cannot move vertically faster
 * than a few m/s, so any step faster than the physical bound is an artifact and is clamped:
 * ```
 * maxDelta = maxRate × dt                       // dt = seconds since the last accepted sample
 * fed      = lastAccepted + clamp(alt − lastAccepted, ±maxDelta)
 * ```
 *
 * **Why the ascent bound sits below the hysteresis.** 1.5 m/s × 3 s = 4.5 m < 5 m, so a clamped
 * step can never cross the accumulator's hysteresis: single-fix artifacts can never book gain. The
 * descent bound is looser (2.5 m/s) because genuine downhill running exceeds 1.5 m/s in bursts; the
 * cost is that descent artifacts above the hysteresis can book phantom loss, which the DEM pass
 * recomputes exactly at save time.
 *
 * The gate bounds *speed*, not distance: a dropped fix or a pause relaxes [maxDelta] with [dt], so
 * a slow-but-large real movement across a gap is not clamped.
 */
class ElevationRateGate(private var config: Config = Config.DEFAULT) {

    /**
     * Tuning constants.
     *
     * @property ascentMaxMps the fastest plausible sustained vertical climb, in m/s.
     * @property descentMaxMps the fastest plausible sustained vertical descent; looser than the
     *   ascent bound because downhill running is genuinely faster.
     */
    data class Config(val ascentMaxMps: Double = 1.5, val descentMaxMps: Double = 2.5) {
        companion object {
            /** The production configuration. */
            val DEFAULT = Config()
        }
    }

    /**
     * Swaps the bounds. Called whenever the user changes the elevation settings; the anchor is
     * untouched, so only subsequent steps are clamped differently.
     */
    fun configure(newConfig: Config) {
        config = newConfig
    }

    private var lastTMs: Long? = null
    private var lastAltM: Double = 0.0

    /**
     * Returns the altitude to feed to the accumulator: the raw value when the step is physically
     * plausible, or the last accepted value plus the maximum plausible step otherwise.
     */
    fun accept(tMs: Long, altM: Double): Double {
        val lastT = lastTMs
        if (lastT == null) {
            lastTMs = tMs
            lastAltM = altM
            return altM
        }
        val dtSeconds = ((tMs - lastT) / 1000.0).coerceAtLeast(0.0)
        val maxRate = if (altM >= lastAltM) config.ascentMaxMps else config.descentMaxMps
        val maxDelta = maxRate * dtSeconds
        val rawDelta = altM - lastAltM
        val fed = lastAltM + rawDelta.coerceIn(-maxDelta, maxDelta)
        lastTMs = tMs
        lastAltM = fed
        return fed
    }

    /**
     * Re-anchors the gate to [altM] at [tMs] without clamping. Called when the barometer completes
     * GNSS calibration, alongside [ElevationStats.reseed], so the baseline snap is not treated as a
     * step.
     */
    fun reseed(tMs: Long, altM: Double) {
        lastTMs = tMs
        lastAltM = altM
    }

    /** Resets the gate for a new track. */
    fun reset() {
        lastTMs = null
        lastAltM = 0.0
    }
}
