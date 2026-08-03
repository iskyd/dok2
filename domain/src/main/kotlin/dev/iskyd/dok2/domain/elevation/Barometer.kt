package dev.iskyd.dok2.domain.elevation

import kotlin.math.pow

/**
 * Barometric altitude estimation with GNSS calibration and drift correction, per DOCUMENTATION.md
 * §Elevation — Live — barometer.
 *
 * Standard formula: `altitude = 44330 × (1 − (p / p0)^(1/5.255))`.
 *
 * **Calibration.** For the first 60 seconds after [start], good GNSS altitudes (accuracy better
 * than 15 m) and the concurrent pressure samples are collected. When the window closes, the medians
 * are used to solve for `p0` so the barometric altitude matches the GNSS altitude. If no usable
 * GNSS altitude arrives, `p0` stays at the fallback of 1013.25 hPa and [calibrated] is false.
 *
 * **Drift correction.** Weather shifts the baseline by 50–100 m over a day. Every 5 minutes, `p0`
 * is nudged toward the median of recent good GNSS altitudes:
 * ```
 * error = median(recent good GNSS altitudes) − current barometric altitude
 * p0   += clamp(error × kDrift, −0.5, +0.5)     // ≈30 min time constant
 * ```
 *
 * The clamp is what makes this safe: one bad GNSS altitude cannot yank the baseline, but a genuine
 * weather front is tracked over half an hour.
 *
 * Pure logic, no timers, no Android: the service feeds it pressure and GNSS altitude samples with
 * their own timestamps.
 */
class Barometer(private val config: Config = Config.DEFAULT) {

    /**
     * Tuning constants for calibration and drift correction.
     *
     * @property calibrationWindowMs how long the initial GNSS altitude collection lasts.
     * @property goodAccuracyMaxM altitudes with accuracy better than this are "good".
     * @property fallbackSeaLevelHpa used when no usable GNSS altitude arrives.
     * @property driftIntervalMs how often the baseline is corrected.
     * @property driftWindowCount how many recent good GNSS altitudes the drift median is taken
     *   over.
     * @property kDrift the fraction of the drift error applied per correction step — 1/6 over a
     *   5-minute step gives roughly the documented 30-minute time constant.
     * @property maxDriftStepHpa the per-step clamp on the baseline correction.
     */
    data class Config(
        val calibrationWindowMs: Long = 60_000L,
        val goodAccuracyMaxM: Double = 15.0,
        val fallbackSeaLevelHpa: Double = 1013.25,
        val driftIntervalMs: Long = 300_000L,
        val driftWindowCount: Int = 10,
        val kDrift: Double = 1.0 / 6.0,
        val maxDriftStepHpa: Double = 0.5,
    ) {
        companion object {
            /** The production configuration. */
            val DEFAULT = Config()
        }
    }

    private var p0: Double = config.fallbackSeaLevelHpa
    private var started: Boolean = false
    private var startTMs: Long = 0L
    private var calibratedValue: Boolean = false
    private var latestPressureHpa: Double? = null
    private var lastDriftTMs: Long? = null

    private val calibrationGnssAlts = mutableListOf<Double>()
    private val calibrationPressures = mutableListOf<Double>()
    private val driftAlts = ArrayDeque<Double>()

    /** The current sea-level pressure `p0` in hPa. */
    val seaLevelHpa: Double
        get() = p0

    /** True once `p0` has been solved against GNSS altitudes. */
    val calibrated: Boolean
        get() = calibratedValue

    /**
     * The current barometric altitude in metres, derived from the latest pressure sample and
     * [seaLevelHpa], or null before any pressure sample arrives.
     */
    val currentBarometricAltitudeM: Double?
        get() = latestPressureHpa?.let { altitudeFrom(it, p0) }

    /**
     * Starts the calibration window. The service calls this when recording starts; if it is not
     * called, the first sample starts the window.
     */
    fun start(tMs: Long) {
        if (started) return
        started = true
        startTMs = tMs
    }

    /** Feeds a barometer pressure sample in hPa. */
    fun onPressure(tMs: Long, pressureHpa: Double) {
        if (!started) start(tMs)
        latestPressureHpa = pressureHpa
        if (!calibratedValue && tMs < startTMs + config.calibrationWindowMs) {
            calibrationPressures += pressureHpa
        }
    }

    /**
     * Feeds a GNSS altitude (in metres above the WGS84 ellipsoid) with its reported horizontal
     * accuracy. Altitudes with accuracy worse than [Config.goodAccuracyMaxM] are ignored. Samples
     * inside the calibration window are collected; afterwards they drive the periodic drift
     * correction.
     */
    fun onGnssAltitude(tMs: Long, altGnssM: Double, accuracyM: Double) {
        if (!started) start(tMs)
        if (accuracyM >= config.goodAccuracyMaxM) return
        if (!calibratedValue && tMs < startTMs + config.calibrationWindowMs) {
            calibrationGnssAlts += altGnssM
            return
        }
        if (!calibratedValue) {
            calibrate()
            return
        }
        driftAlts.addLast(altGnssM)
        while (driftAlts.size > config.driftWindowCount) {
            driftAlts.removeFirst()
        }
        if (lastDriftTMs == null || tMs - lastDriftTMs!! >= config.driftIntervalMs) {
            applyDrift(tMs)
        }
    }

    /** Resets all state so the instance can be reused for a new track. */
    fun reset() {
        p0 = config.fallbackSeaLevelHpa
        started = false
        startTMs = 0L
        calibratedValue = false
        latestPressureHpa = null
        lastDriftTMs = null
        calibrationGnssAlts.clear()
        calibrationPressures.clear()
        driftAlts.clear()
    }

    private fun calibrate() {
        val altitudeMedian = median(calibrationGnssAlts) ?: return
        val pressureMedian = median(calibrationPressures) ?: return
        // The hypsometric formula is only defined below ~44330 m; guard against a
        // pathological GNSS altitude producing a NaN baseline.
        if (altitudeMedian >= 44330.0) return
        p0 = pressureMedian / (1.0 - altitudeMedian / 44330.0).pow(5.255)
        calibratedValue = true
    }

    private fun applyDrift(tMs: Long) {
        val altitudeMedian = median(driftAlts) ?: return
        val currentAltitude = currentBarometricAltitudeM ?: return
        // The clamp bounds how far one correction step may move the baseline: a single
        // bad GNSS altitude cannot yank it, while a genuine weather front is still
        // tracked over the ~30 min time constant.
        val error = altitudeMedian - currentAltitude
        val step = (error * config.kDrift).coerceIn(-config.maxDriftStepHpa, config.maxDriftStepHpa)
        p0 += step
        lastDriftTMs = tMs
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    companion object {
        /**
         * The barometric formula: altitude in metres from pressure and sea-level pressure in hPa.
         */
        fun altitudeFrom(pressureHpa: Double, seaLevelHpa: Double): Double =
            44_330.0 * (1.0 - (pressureHpa / seaLevelHpa).pow(1.0 / 5.255))
    }
}
