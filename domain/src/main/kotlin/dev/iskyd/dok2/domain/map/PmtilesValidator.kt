package dev.iskyd.dok2.domain.map

import java.io.IOException
import java.io.InputStream

/**
 * The outcome of validating a PMTiles archive header.
 *
 * [Valid] carries the parsed tile type; [Invalid] carries a human-readable reason. The `:data`
 * layer uses the reason to tell the user why a picked file was rejected.
 */
sealed interface PmtilesValidationResult {

    /** The header is a well-formed PMTiles archive of an accepted tile type. */
    data class Valid(val tileType: Int) : PmtilesValidationResult

    /** The header failed validation; [reason] describes what was wrong. */
    data class Invalid(val reason: String) : PmtilesValidationResult
}

/**
 * Validates the fixed 127-byte header of a PMTiles archive, read from the supplied stream.
 *
 * Pure validation only — the stream is consumed for the 127 header bytes and nothing more; the root
 * directory, metadata and tile data are deliberately not touched (the `:data` layer copies the rest
 * of the stream after this check passes).
 *
 * Byte layout checked, per the PMTiles v3 spec (protomaps/PMTiles `spec/v3/spec.md`, cross-checked
 * against the reference C++ and JS implementations, which Tilemaker uses):
 * ```
 * offset  size  field
 * 0       7     magic number "PMTiles" (50 4D 54 69 6C 65 73)
 * 7       1     spec version (must be 3)
 * 8       96    root directory, metadata, tile data offsets/lengths, counts, compression
 * 99      1     tile type (1 = MVT vector, 2 = PNG, 3 = JPEG, ...)
 * 100     27    zooms and positions
 * ```
 *
 * A header is valid iff it is a full 127 bytes, carries the magic bytes, is spec version 3, and
 * declares tile type 1 (MVT vector). Raster tile types are rejected even though MapLibre can load
 * them: they would render nothing on the basemap and waste the user's storage. The earlier plan
 * note ("version at bytes 8-9 big-endian, tile_type at offset 13") matches no published spec and no
 * real archive — the offsets above are the ones the reference implementations write.
 */
class PmtilesValidator {

    /**
     * Validates the header of a PMTiles archive.
     *
     * Returns [PmtilesValidationResult.Invalid] on a short read, EOF, or any [IOException] thrown
     * by the stream, so callers never have to handle stream errors separately.
     */
    fun validateHeader(input: InputStream): PmtilesValidationResult {
        val header = ByteArray(HEADER_SIZE)
        val bytesRead =
            try {
                readFully(input, header)
            } catch (e: IOException) {
                return PmtilesValidationResult.Invalid("could not read header: ${e.message}")
            }
        if (bytesRead < HEADER_SIZE) {
            return PmtilesValidationResult.Invalid(
                "header truncated: read $bytesRead of $HEADER_SIZE bytes"
            )
        }
        if (!hasMagic(header)) {
            return PmtilesValidationResult.Invalid("not a PMTiles archive: bad magic bytes")
        }
        val version = header[VERSION_OFFSET].toInt() and 0xFF
        if (version != SPEC_VERSION) {
            return PmtilesValidationResult.Invalid(
                "unsupported PMTiles spec version $version (expected $SPEC_VERSION)"
            )
        }
        val tileType = header[TILE_TYPE_OFFSET].toInt() and 0xFF
        if (tileType != TILE_TYPE_MVT) {
            return PmtilesValidationResult.Invalid(
                "unsupported tile type $tileType (expected $TILE_TYPE_MVT = MVT vector)"
            )
        }
        return PmtilesValidationResult.Valid(tileType)
    }

    private fun hasMagic(header: ByteArray): Boolean =
        MAGIC_BYTES.indices.all { header[it] == MAGIC_BYTES[it] }

    /** Reads as many bytes as the buffer can hold, stopping early only on EOF. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) break
            offset += read
        }
        return offset
    }

    private companion object {
        /** Fixed PMTiles header size in bytes (v3 spec). */
        const val HEADER_SIZE = 127

        /** Magic number "PMTiles" (50 4D 54 69 6C 65 73). */
        val MAGIC_BYTES = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73)

        /** Spec version, a single byte at offset 7. */
        const val VERSION_OFFSET = 7

        /** The only spec version modern writers emit (pmtiles CLI, Tilemaker, Martin). */
        const val SPEC_VERSION = 3

        /** Tile type field, a single byte at offset 99. */
        const val TILE_TYPE_OFFSET = 99

        /** MVT vector tiles — the only tile type this app renders. */
        const val TILE_TYPE_MVT = 1
    }
}
