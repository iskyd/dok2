package dev.iskyd.dok2.domain.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure spherical geometry helpers used by the filter chain and the GPX writer.
 *
 * All functions take coordinates in decimal degrees and return metres or degrees. The Earth is
 * modelled as a sphere with the mean Earth radius.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    private const val DEG_TO_RAD = Math.PI / 180.0

    private const val RAD_TO_DEG = 180.0 / Math.PI

    /** One degree of latitude is ~111.32 km. */
    private const val METRES_PER_DEGREE_LAT = 111_320.0

    /** Great-circle distance in metres between two coordinates (haversine). */
    fun distanceM(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
        val phi1 = lat1Deg * DEG_TO_RAD
        val phi2 = lat2Deg * DEG_TO_RAD
        val dPhi = (lat2Deg - lat1Deg) * DEG_TO_RAD
        val dLambda = (lon2Deg - lon1Deg) * DEG_TO_RAD
        val a =
            sin(dPhi / 2.0) * sin(dPhi / 2.0) +
                cos(phi1) * cos(phi2) * sin(dLambda / 2.0) * sin(dLambda / 2.0)
        return 2.0 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /**
     * Initial great-circle bearing from the first coordinate towards the second, in degrees
     * clockwise from true north, in the range [0, 360).
     */
    fun initialBearingDeg(
        lat1Deg: Double,
        lon1Deg: Double,
        lat2Deg: Double,
        lon2Deg: Double,
    ): Double {
        val phi1 = lat1Deg * DEG_TO_RAD
        val phi2 = lat2Deg * DEG_TO_RAD
        val dLambda = (lon2Deg - lon1Deg) * DEG_TO_RAD
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return ((atan2(y, x) * RAD_TO_DEG) + 360.0) % 360.0
    }

    /**
     * Destination point after moving [distanceMeters] along the given initial bearing, in decimal
     * degrees. Useful for building synthetic fixtures.
     */
    fun destinationPoint(
        latDeg: Double,
        lonDeg: Double,
        bearingDeg: Double,
        distanceMeters: Double,
    ): GeoPoint {
        val phi1 = latDeg * DEG_TO_RAD
        val lambda1 = lonDeg * DEG_TO_RAD
        val theta = bearingDeg * DEG_TO_RAD
        val delta = distanceMeters / EARTH_RADIUS_M
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 =
            lambda1 + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
        return GeoPoint(phi2 * RAD_TO_DEG, ((lambda2 * RAD_TO_DEG + 540.0) % 360.0) - 180.0)
    }

    /** A lat/lon pair in decimal degrees. */
    data class GeoPoint(val latDeg: Double, val lonDeg: Double)
}
