package dev.iskyd.dok2.domain.export

import com.google.common.truth.Truth.assertThat
import dev.iskyd.dok2.domain.geo.Geo
import dev.iskyd.dok2.domain.model.PointState
import dev.iskyd.dok2.domain.model.Track
import dev.iskyd.dok2.domain.model.TrackPoint
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

class GpxWriterTest {

    private val writer = GpxWriter(geoidOffsetM = { _, _ -> 45.0 })

    private val iso8601UtcRegex = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")

    private fun track(name: String? = "Morning hike") =
        Track(id = 1, name = name, startedAtMs = 1_700_000_000_000)

    private fun point(
        tMs: Long,
        latDeg: Double,
        lonDeg: Double,
        altGnssM: Double? = 1000.0,
        state: PointState = PointState.RECORDING,
    ) =
        TrackPoint(
            tMs = tMs,
            latDeg = latDeg,
            lonDeg = lonDeg,
            accuracyM = 5.0,
            altGnssM = altGnssM,
            state = state,
            accumulated = true,
        )

    private fun parse(gpx: String): Document =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(gpx)))

    @Test
    fun `produces a well-formed gpx 1-1 document`() {
        val doc = parse(writer.write(track(), listOf(point(0, 47.0, 8.0))))

        val root = doc.documentElement
        assertThat(root.tagName).isEqualTo("gpx")
        assertThat(root.getAttribute("version")).isEqualTo("1.1")
        assertThat(root.getAttribute("xmlns")).isEqualTo("http://www.topografix.com/GPX/1/1")
        assertThat(root.getElementsByTagName("trk").length).isEqualTo(1)
        assertThat(root.getElementsByTagName("trkpt").length).isEqualTo(1)
    }

    @Test
    fun `name and activity type are exported`() {
        val doc = parse(writer.write(track(name = "Hike & Run"), listOf(point(0, 47.0, 8.0))))

        assertThat(doc.getElementsByTagName("name").item(0).textContent).isEqualTo("Hike & Run")
        assertThat(doc.getElementsByTagName("type").item(0).textContent).isEqualTo("hike")
    }

    @Test
    fun `paused segments are exported as separate trkseg elements`() {
        val points =
            listOf(
                point(0, 47.0, 8.0, state = PointState.RECORDING),
                point(3_000, 47.0001, 8.0, state = PointState.RECORDING),
                point(6_000, 47.0002, 8.0, state = PointState.MANUAL_PAUSED),
                point(9_000, 47.0003, 8.0, state = PointState.MANUAL_PAUSED),
                point(12_000, 47.0004, 8.0, state = PointState.RECORDING),
            )
        val doc = parse(writer.write(track(), points))

        val segments = doc.getElementsByTagName("trkseg")
        assertThat(segments.length).isEqualTo(3)
        assertThat((segments.item(0) as Element).getElementsByTagName("trkpt").length).isEqualTo(2)
        assertThat((segments.item(1) as Element).getElementsByTagName("trkpt").length).isEqualTo(2)
        assertThat((segments.item(2) as Element).getElementsByTagName("trkpt").length).isEqualTo(1)
    }

    @Test
    fun `xml special characters are escaped and round-trip`() {
        val name = "A & B < C > D \"Q\" 'A'"
        val gpx = writer.write(track(name = name), listOf(point(0, 47.0, 8.0)))

        assertThat(gpx).contains("A &amp; B &lt; C &gt; D &quot;Q&quot; &apos;A&apos;")
        assertThat(parse(gpx).getElementsByTagName("name").item(0).textContent).isEqualTo(name)
    }

    @Test
    fun `elevation is exported as egm2008 geoid height`() {
        val doc = parse(writer.write(track(), listOf(point(0, 47.0, 8.0, altGnssM = 1000.0))))

        // WGS84 ellipsoidal height minus the 45 m geoid undulation.
        assertThat(doc.getElementsByTagName("ele").item(0).textContent.trim()).isEqualTo("955")
    }

    @Test
    fun `points without a gnss altitude omit the ele element`() {
        val doc = parse(writer.write(track(), listOf(point(0, 47.0, 8.0, altGnssM = null))))

        assertThat(doc.getElementsByTagName("ele").length).isEqualTo(0)
    }

    @Test
    fun `time is exported as iso 8601 utc`() {
        val doc = parse(writer.write(track(), listOf(point(1_700_000_000_000, 47.0, 8.0))))

        val time = doc.getElementsByTagName("time").item(0).textContent.trim()
        assertThat(time.matches(iso8601UtcRegex)).isTrue()
        assertThat(time).isEqualTo("2023-11-14T22:13:20Z")
    }

    @Test
    fun `privacy zone trims both ends of the track`() {
        // 50 m steps east from home, out to 1200 m and back. Home at 0 m, radius 120 m.
        val out = (0..24).map { destinationAt(50.0 * it) }
        val back = (24 downTo 0).map { destinationAt(50.0 * it) }
        val points =
            (out + back).mapIndexed { index, geo ->
                point(tMs = index * 3_000L, latDeg = geo.latDeg, lonDeg = geo.lonDeg)
            }
        val zone = PrivacyZone(homeLatDeg = 47.0, homeLonDeg = 8.0, radiusM = 120.0)

        val doc = parse(writer.write(track(), points, privacyZone = zone))
        val trkpts = doc.getElementsByTagName("trkpt")

        // Points at 0, 50 and 100 m are trimmed from both ends: 50 - 6 = 44 remain.
        assertThat(trkpts.length).isEqualTo(44)
        // The first and last kept points are the 150 m out and back positions.
        val expected = destinationAt(150.0)
        assertThat(latOf(trkpts.item(0).attributes.getNamedItem("lat").nodeValue))
            .isWithin(1e-6)
            .of(expected.latDeg)
        assertThat(latOf(trkpts.item(trkpts.length - 1).attributes.getNamedItem("lat").nodeValue))
            .isWithin(1e-6)
            .of(expected.latDeg)
    }

    private fun destinationAt(distanceMeters: Double) =
        Geo.destinationPoint(47.0, 8.0, bearingDeg = 90.0, distanceMeters = distanceMeters)

    private fun latOf(value: String): Double = value.toDouble()
}
