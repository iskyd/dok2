package dev.iskyd.dok2.domain.map

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.fail
import org.junit.Test

class PmtilesValidatorTest {

    private val validator = PmtilesValidator()

    @Test
    fun `valid mvt header is accepted`() {
        val result = validator.validateHeader(ByteArrayInputStream(header()))

        when (result) {
            is PmtilesValidationResult.Valid -> assertThat(result.tileType).isEqualTo(1)
            is PmtilesValidationResult.Invalid ->
                fail("expected Valid, got Invalid(${result.reason})")
        }
    }

    @Test
    fun `truncated header is invalid`() {
        val truncated = header().copyOfRange(0, 10)

        assertInvalid(validator.validateHeader(ByteArrayInputStream(truncated)))
    }

    @Test
    fun `empty stream is invalid`() {
        assertInvalid(validator.validateHeader(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `wrong magic bytes are invalid`() {
        val badMagic = header(magic = "XXXXXXX".toByteArray(Charsets.US_ASCII))

        assertInvalid(validator.validateHeader(ByteArrayInputStream(badMagic)))
    }

    @Test
    fun `spec version 1 is invalid`() {
        assertInvalid(validator.validateHeader(ByteArrayInputStream(header(version = 1))))
    }

    @Test
    fun `spec version 2 is invalid`() {
        assertInvalid(validator.validateHeader(ByteArrayInputStream(header(version = 2))))
    }

    @Test
    fun `png raster tile type is invalid`() {
        assertInvalid(validator.validateHeader(ByteArrayInputStream(header(tileType = 2))))
    }

    @Test
    fun `jpeg raster tile type is invalid`() {
        assertInvalid(validator.validateHeader(ByteArrayInputStream(header(tileType = 3))))
    }

    @Test
    fun `stream that throws on read is invalid`() {
        assertInvalid(validator.validateHeader(ThrowingInputStream()))
    }

    private fun assertInvalid(result: PmtilesValidationResult) {
        when (result) {
            is PmtilesValidationResult.Valid ->
                fail("expected Invalid, got Valid(tileType=${result.tileType})")
            is PmtilesValidationResult.Invalid -> assertThat(result.reason.trim()).isNotEmpty()
        }
    }

    /**
     * A fixed 127-byte PMTiles v3 header: magic "PMTiles" at bytes 0-6, spec version at byte 7,
     * tile type at byte 99. Every other field is zero.
     */
    private fun header(
        magic: ByteArray = "PMTiles".toByteArray(Charsets.US_ASCII),
        version: Int = 3,
        tileType: Int = 1,
    ): ByteArray {
        val header = ByteArray(127)
        magic.copyInto(header, 0)
        header[7] = version.toByte()
        header[99] = tileType.toByte()
        return header
    }

    /** An InputStream whose read() always throws, exercising IOException handling. */
    private class ThrowingInputStream : InputStream() {
        override fun read(): Int = throw IOException("boom")
    }
}
