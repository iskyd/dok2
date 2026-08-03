package dev.iskyd.dok2.domain

import dev.iskyd.dok2.domain.model.GpsFix

/**
 * Test helpers for the replay harness.
 *
 * Fixtures live in `domain/src/test/resources/fixtures/` as JSONL of raw fixes (captured by the
 * app's debug recording mode, or synthetic stand-ins). Each line is one [GpsFix] with the fields
 * `t_ms`, `lat`, `lon`, `accuracy_m`, and optional `alt_gnss_m`, `speed_mps`, `pressure_hpa`.
 * Parsing is deliberately hand-rolled — no new dependencies.
 */
object TestFixtures {

    /** Reads and parses every line of the named fixture file into a [List] of [GpsFix]es. */
    fun loadFixture(name: String): List<GpsFix> {
        val resource = "/fixtures/$name"
        val stream =
            checkNotNull(TestFixtures::class.java.getResourceAsStream(resource)) {
                "fixture not found: $name"
            }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { parseFixLine(it) }.toList()
        }
    }

    private fun parseFixLine(line: String): GpsFix {
        val inner = line.trim().removePrefix("{").removeSuffix("}")
        val fields = mutableMapOf<String, String>()
        for (part in inner.split(',')) {
            val separator = part.indexOf(':')
            val key = part.substring(0, separator).trim().trim('"')
            val value = part.substring(separator + 1).trim()
            fields[key] = value
        }

        fun optDouble(key: String): Double? = fields[key]?.takeIf { it != "null" }?.toDouble()
        fun double(key: String): Double =
            optDouble(key) ?: error("missing field '$key' in line: $line")
        fun long(key: String): Long =
            fields[key]?.toLong() ?: error("missing field '$key' in line: $line")

        return GpsFix(
            tMs = long("t_ms"),
            latDeg = double("lat"),
            lonDeg = double("lon"),
            accuracyM = double("accuracy_m"),
            altGnssM = optDouble("alt_gnss_m"),
            speedMps = optDouble("speed_mps"),
            bearingDeg = optDouble("bearing_deg"),
            pressureHpa = optDouble("pressure_hpa"),
        )
    }
}
