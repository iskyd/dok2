package dev.iskyd.dok2.domain.model

/**
 * The recording state a trackpoint was captured in. The integer [code] matches the `state` column
 * of the `trackpoints` table (0 recording, 1 auto-paused, 2 manual-paused).
 *
 * Points are stored in every state — pausing affects accumulation, not storage.
 */
enum class PointState(val code: Int) {
    RECORDING(0),
    AUTO_PAUSED(1),
    MANUAL_PAUSED(2),
}

/**
 * A single stored point of a track, at the domain layer.
 *
 * Coordinates are decimal degrees as [Double]. The Int 1e-7 conversion happens at the `:data`
 * boundary and never here.
 *
 * Raw sensor values ([altGnssM], [pressureHpa]) are stored verbatim so derived figures can be
 * recomputed later — they are never overwritten.
 *
 * @property tMs epoch millis from the GNSS fix.
 * @property latDeg latitude in decimal degrees.
 * @property lonDeg longitude in decimal degrees.
 * @property accuracyM reported horizontal accuracy in metres.
 * @property altGnssM raw GNSS altitude above the WGS84 ellipsoid, if reported.
 * @property pressureHpa raw barometer reading, if reported.
 * @property speedMps speed reported by the receiver, if any (not used for filtering).
 * @property bearingDeg heading reported by the receiver, if any.
 * @property state the recording state this point was captured in.
 * @property accumulated true if this point advanced the filter chain anchor (and so contributed to
 *   the odometer).
 */
data class TrackPoint(
    val tMs: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val accuracyM: Double,
    val altGnssM: Double? = null,
    val pressureHpa: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val state: PointState,
    val accumulated: Boolean,
)
