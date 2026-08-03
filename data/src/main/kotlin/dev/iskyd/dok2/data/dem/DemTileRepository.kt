package dev.iskyd.dok2.data.dem

import android.content.Context
import dev.iskyd.dok2.domain.elevation.HgtReader
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads raw SRTM `.hgt` tiles from a user-supplied directory (DOCUMENTATION.md §Map and elevation
 * data). A thin loader: it resolves the 1-degree cell file for a coordinate, validates the file
 * size and hands the bytes to the pure [HgtReader]. All interpolation happens in `:domain`.
 *
 * Only the two SRTM grid sizes are accepted: 1 arc-second (3601×3601, ~30 m resolution) and 3
 * arc-seconds (1201×1201).
 */
class DemTileRepository(private val context: Context) {

    // Only the most recently loaded cell is kept — a cached tile set would cost hundreds of MB.
    // The caller (the recording service) can remember a cell across a track if it wants.
    private var lastLoaded: Pair<File, HgtReader>? = null

    /**
     * Loads the SRTM cell containing the given coordinate, or null when the cell file is missing or
     * malformed. The caller falls back to the barometric figures when this returns null rather than
     * crashing the recording.
     */
    suspend fun loadHgt(latDeg: Double, lonDeg: Double, directory: File): HgtReader? {
        val file = hgtFile(latDeg, lonDeg, directory)
        val cached = lastLoaded
        if (cached != null && cached.first == file) return cached.second

        val reader = readHgt(file) ?: return null
        lastLoaded = file to reader
        return reader
    }

    private suspend fun readHgt(file: File): HgtReader? {
        if (!file.isFile) return null
        return withContext(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                val size =
                    when (bytes.size) {
                        SRTM_1_SIZE -> 3601
                        SRTM_3_SIZE -> 1201
                        else -> return@withContext null
                    }
                HgtReader.fromHgt(bytes, size)
            } catch (error: IOException) {
                // A torn or unreadable tile is treated as missing rather than crashing the
                // recording; the caller falls back to the barometric figures.
                null
            }
        }
    }

    /**
     * Resolves the SRTM filename for the 1-degree cell whose south-west corner contains the
     * coordinate, e.g. `N45E008.hgt`. Negative latitudes/longitudes use the `S`/`W` prefixes with
     * the absolute cell index — a point at -45.5° lies in cell `S46`.
     */
    private fun hgtFile(latDeg: Double, lonDeg: Double, directory: File): File {
        val latIndex = floor(latDeg).toInt()
        val lonIndex = floor(lonDeg).toInt()
        val latPrefix = if (latIndex >= 0) "N" else "S"
        val lonPrefix = if (lonIndex >= 0) "E" else "W"
        val name =
            String.format("%s%02d%s%03d.hgt", latPrefix, abs(latIndex), lonPrefix, abs(lonIndex))
        return File(directory, name)
    }

    private companion object {
        /** Byte size of a 1 arc-second SRTM cell (3601×3601 int16 samples). */
        const val SRTM_1_SIZE = 3601 * 3601 * 2

        /** Byte size of a 3 arc-second SRTM cell (1201×1201 int16 samples). */
        const val SRTM_3_SIZE = 1201 * 1201 * 2
    }
}
