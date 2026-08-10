package dev.iskyd.dok2.domain.elevation

import dev.iskyd.dok2.domain.geo.Geo
import dev.iskyd.dok2.domain.model.TrackPoint

/**
 * Selects the points of a recorded path that feed the DEM gain/loss accumulator at save time.
 *
 * Per DOCUMENTATION.md (Elevation — Final), the DEM pass must be fed one point per ~15 m of path,
 * not every stored point: at the 3 s recording interval stored points are only ~4–8 m apart, so
 * their positions carry GNSS noise that the coarse SRTM grid resolves as altitude undulation of
 * sub-sample wavelength. Fed at full resolution, that undulation repeatedly trips the 5 m
 * hysteresis in both directions and gain/loss inflate symmetrically on mountainous tracks. The ~15
 * m spacing keeps the real terrain gradient (a few metres per sample) comparable to the noise
 * instead of drowned by it.
 *
 * The first and last points are always kept (so the final segment's vertical drop is not cut off);
 * every other kept point is at least [minSpacingM] from the previous kept one, measured with the
 * haversine distance.
 */
object ElevationResampler {

    /** Returns the indices of [points] to feed to the DEM gain/loss accumulator, in order. */
    fun indices(points: List<TrackPoint>, minSpacingM: Double = 15.0): List<Int> {
        if (points.size < 2) return points.indices.toList()
        val kept = mutableListOf(0)
        var lastKept = points[0]
        for (i in 1 until points.size) {
            val point = points[i]
            if (
                Geo.distanceM(lastKept.latDeg, lastKept.lonDeg, point.latDeg, point.lonDeg) >=
                    minSpacingM
            ) {
                kept += i
                lastKept = point
            }
        }
        val lastIndex = points.lastIndex
        if (kept.last() != lastIndex) kept += lastIndex
        return kept
    }
}
