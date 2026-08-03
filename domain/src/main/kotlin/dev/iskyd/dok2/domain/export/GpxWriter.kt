package dev.iskyd.dok2.domain.export

import dev.iskyd.dok2.domain.geo.Geo
import dev.iskyd.dok2.domain.model.Track
import dev.iskyd.dok2.domain.model.TrackPoint
import java.time.Instant
import java.util.Locale

/**
 * The privacy zone used to trim exported tracks: points within [radiusM] of the home point are
 * removed at both ends of the track. Off by default.
 */
data class PrivacyZone(val homeLatDeg: Double, val homeLonDeg: Double, val radiusM: Double)

/**
 * GPX 1.1 writer, per DOCUMENTATION.md §Export.
 * - Elevation is exported as EGM2008 geoid height: the stored WGS84 ellipsoidal height minus the
 *   geoid undulation returned by [geoidOffsetM] (the EGM2008 model lives in `:data`/`:app`; the
 *   domain only applies the conversion).
 * - Points captured in different recording states are written as separate `<trkseg>` elements, so
 *   paused segments are visible in the exported file.
 * - A [PrivacyZone] optionally trims the track at both ends.
 *
 * The XML is built by hand with proper escaping — no XML dependencies beyond the JVM stdlib.
 *
 * @property geoidOffsetM returns the EGM2008 geoid undulation in metres at a lat/lon pair, so the
 *   exported elevation is `altGnssM - geoidOffsetM(lat, lon)`.
 * @property creator the value of the GPX `creator` attribute.
 */
class GpxWriter(
    private val geoidOffsetM: (latDeg: Double, lonDeg: Double) -> Double,
    private val creator: String = "dok2",
) {

    /**
     * Serialises [points] as a GPX 1.1 document for [track].
     *
     * @param track the track metadata (name, activity type).
     * @param points the trackpoints to export, in order.
     * @param privacyZone when non-null, points within the zone at both ends of the track are
     *   trimmed before export.
     */
    fun write(track: Track, points: List<TrackPoint>, privacyZone: PrivacyZone? = null): String {
        val trimmed = if (privacyZone != null) trimPrivacy(points, privacyZone) else points

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"").append(escapeXml(creator)).append("\"")
        sb.append(" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        sb.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        sb.append(
            " xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\""
        )
        sb.append(">\n")
        sb.append("  <trk>\n")
        track.name?.let { sb.append("    <name>").append(escapeXml(it)).append("</name>\n") }
        sb.append("    <type>").append(escapeXml(track.activityType)).append("</type>\n")

        var index = 0
        while (index < trimmed.size) {
            val segmentState = trimmed[index].state
            sb.append("    <trkseg>\n")
            while (index < trimmed.size && trimmed[index].state == segmentState) {
                sb.append(trkpt(trimmed[index]))
                index++
            }
            sb.append("    </trkseg>\n")
        }

        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun trkpt(point: TrackPoint): String {
        val sb = StringBuilder()
        sb.append("      <trkpt lat=\"").append(formatCoord(point.latDeg))
        sb.append("\" lon=\"").append(formatCoord(point.lonDeg)).append("\">\n")
        point.altGnssM?.let { altitude ->
            val geoidHeight = altitude - geoidOffsetM(point.latDeg, point.lonDeg)
            sb.append("        <ele>").append(formatNumber(geoidHeight)).append("</ele>\n")
        }
        sb.append("        <time>")
            .append(Instant.ofEpochMilli(point.tMs).toString())
            .append("</time>\n")
        sb.append("      </trkpt>\n")
        return sb.toString()
    }

    private fun trimPrivacy(points: List<TrackPoint>, zone: PrivacyZone): List<TrackPoint> {
        fun inZone(point: TrackPoint): Boolean =
            Geo.distanceM(zone.homeLatDeg, zone.homeLonDeg, point.latDeg, point.lonDeg) <=
                zone.radiusM

        val firstOutside = points.indexOfFirst { !inZone(it) }
        if (firstOutside == -1) return emptyList()
        val lastOutside = points.indexOfLast { !inZone(it) }
        return points.subList(firstOutside, lastOutside + 1)
    }

    private fun escapeXml(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun formatCoord(value: Double): String =
        stripTrailingZeros(String.format(Locale.ROOT, "%.7f", value))

    private fun formatNumber(value: Double): String =
        stripTrailingZeros(String.format(Locale.ROOT, "%.1f", value))

    private fun stripTrailingZeros(value: String): String =
        if ('.' in value) value.trimEnd('0').trimEnd('.') else value
}
