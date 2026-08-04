package dev.iskyd.dok2.data.db

/**
 * The aggregate projection produced by [TrackPointDao.getAltitudeExtremes]: the lowest and highest
 * altitude of a track from each of the two sources. It is mapped to
 * `dev.iskyd.dok2.domain.model.ElevationExtremes` in the repository layer, which prefers the
 * DEM-derived figures when present.
 */
data class AltitudeExtremesRow(
    val minDemM: Double?,
    val maxDemM: Double?,
    val minGnssM: Double?,
    val maxGnssM: Double?,
)
