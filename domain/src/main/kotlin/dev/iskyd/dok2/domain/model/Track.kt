package dev.iskyd.dok2.domain.model

/**
 * A finalised (or in-progress) track, mirroring the `tracks` table schema.
 *
 * Barometric and DEM gain/loss figures are kept separate so the DEM-derived values can be
 * recomputed without touching the raw data. [calibrated] records whether the barometer was
 * calibrated against GNSS altitudes during the first 60 seconds.
 */
data class Track(
    val id: Long,
    val name: String? = null,
    val activityType: String = "hike",
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val distanceM: Double = 0.0,
    val movingTimeS: Long = 0,
    val elapsedTimeS: Long = 0,
    val gainBaroM: Double? = null,
    val lossBaroM: Double? = null,
    val gainDemM: Double? = null,
    val lossDemM: Double? = null,
    val seaLevelHpa: Double? = null,
    val calibrated: Boolean = false,
    val thumbnailPath: String? = null,
    val notes: String? = null,
)

/**
 * The lightweight projection of a track used by the track list UI, which renders pre-generated
 * thumbnails and never instantiates a map view.
 */
data class TrackSummary(
    val id: Long,
    val name: String? = null,
    val startedAtMs: Long,
    val distanceM: Double,
    val elapsedTimeS: Long,
    val gainDemM: Double? = null,
)
