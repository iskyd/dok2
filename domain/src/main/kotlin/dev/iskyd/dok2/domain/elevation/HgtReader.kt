package dev.iskyd.dok2.domain.elevation

import kotlin.math.floor

/**
 * Pure SRTM `.hgt` interpolation — DEM lookup with zero file I/O. Loading the file is `:data`'s
 * job; the raw int16 grid is passed in as a parameter.
 *
 * Supports the two SRTM sizes: 1-arc-second (3601×3601, ~30 m resolution) and 3-arc-second
 * (1201×1201). Any square grid works, which is also what makes tiny in-memory grids usable in
 * tests.
 *
 * Indexing follows DOCUMENTATION.md §Elevation — DEM format:
 * ```
 * row = (1 − (lat − floor(lat))) × (size − 1)
 * col =      (lon − floor(lon))  × (size − 1)
 * ```
 *
 * with bilinear interpolation between the four surrounding cells. Voids (encoded as `-32768`) are
 * skipped: if any of the four cells a query touches is a void, the result is null.
 *
 * @property heights the raw int16 sample values, row-major, `size * size` entries.
 * @property size the grid edge length in samples.
 */
class HgtReader(val heights: IntArray, val size: Int) {

    init {
        require(size > 0) { "grid size must be positive" }
        require(heights.size == size * size) {
            "heights must contain size*size = ${size * size} entries, got ${heights.size}"
        }
    }

    /** Sentinel encoding a void (missing data) in SRTM files. */
    val void: Int = -32768

    /**
     * The interpolated elevation at the given coordinate in metres, or null when the coordinate is
     * out of range or any of the four surrounding samples is a void.
     */
    fun altitudeAt(latDeg: Double, lonDeg: Double): Double? {
        if (latDeg < -90.0 || latDeg > 90.0 || lonDeg < -180.0 || lonDeg > 180.0) return null

        val row = (1.0 - (latDeg - floor(latDeg))) * (size - 1)
        val col = (lonDeg - floor(lonDeg)) * (size - 1)

        val row0 = floor(row).toInt()
        val col0 = floor(col).toInt()
        // Clamp to the last row/column so the outermost samples are never extrapolated.
        val row1 = minOf(row0 + 1, size - 1)
        val col1 = minOf(col0 + 1, size - 1)
        val fracRow = row - row0
        val fracCol = col - col0

        val h00 = heights[row0 * size + col0]
        val h01 = heights[row0 * size + col1]
        val h10 = heights[row1 * size + col0]
        val h11 = heights[row1 * size + col1]
        if (h00 == void || h01 == void || h10 == void || h11 == void) return null

        val top = h00 * (1.0 - fracCol) + h01 * fracCol
        val bottom = h10 * (1.0 - fracCol) + h11 * fracCol
        return top * (1.0 - fracRow) + bottom * fracRow
    }

    companion object {
        /**
         * Builds a reader from the raw big-endian int16 bytes of an `.hgt` file. The byte array
         * must hold exactly `size * size * 2` bytes.
         */
        fun fromHgt(bytes: ByteArray, size: Int): HgtReader {
            require(bytes.size == size * size * 2) {
                "expected ${size * size * 2} bytes for a ${size}x$size grid, got ${bytes.size}"
            }
            val heights = IntArray(size * size)
            for (i in heights.indices) {
                val high = bytes[i * 2].toInt() and 0xFF
                val low = bytes[i * 2 + 1].toInt() and 0xFF
                heights[i] = ((high shl 8) or low).toShort().toInt()
            }
            return HgtReader(heights, size)
        }
    }
}
