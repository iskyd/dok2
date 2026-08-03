package dev.iskyd.dok2.data

import kotlin.math.round

/**
 * Conversion between the domain layer's `Double` decimal degrees and the storage layer's `Int`
 * degrees at 1e-7 resolution, per DOCUMENTATION.md §Data model.
 *
 * Fixed-point storage is roughly 1 cm resolution, half the bytes of a double, and free of
 * floating-point comparison bugs. The conversion happens only here, at the `:data`/`:domain`
 * boundary — never in the middle of a pipeline (AGENTS.md).
 */
object CoordinateCodec {

    /** Converts decimal degrees to fixed-point 1e-7 degrees (e.g. `45.1234567` -> `451234567`). */
    fun toE7(deg: Double): Int = round(deg * 1e7).toInt()

    /** Converts fixed-point 1e-7 degrees back to decimal degrees. */
    fun toDegrees(e7: Int): Double = e7 / 1e7
}
