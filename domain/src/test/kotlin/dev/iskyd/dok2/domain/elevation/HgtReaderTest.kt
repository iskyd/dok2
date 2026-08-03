package dev.iskyd.dok2.domain.elevation

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class HgtReaderTest {

    /** Encodes int16 values as big-endian bytes, as in a raw SRTM `.hgt` file. */
    private fun bigEndian(vararg values: Int): ByteArray {
        val bytes = ByteArray(values.size * 2)
        for ((index, value) in values.withIndex()) {
            bytes[index * 2] = ((value shr 8) and 0xFF).toByte()
            bytes[index * 2 + 1] = (value and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `interpolates bilinearly inside a cell`() {
        // North row [0, 100], south row [200, 300]; size 2 spans the cell 47-48 x 8-9.
        val reader = HgtReader.fromHgt(bigEndian(0, 100, 200, 300), size = 2)

        // The cell centre averages all four corners.
        assertThat(reader.altitudeAt(47.5, 8.5)).isWithin(1e-9).of(150.0)
        // A point near the north-east corner weights those samples most.
        assertThat(reader.altitudeAt(47.9, 8.1)).isWithin(1e-9).of(30.0)
    }

    @Test
    fun `samples the correct corner on the cell edges`() {
        val reader = HgtReader.fromHgt(bigEndian(0, 100, 200, 300), size = 2)

        // lat 47.0 is the south edge (row = size-1), lon 8.0 the west edge.
        assertThat(reader.altitudeAt(47.0, 8.0)).isWithin(1e-9).of(200.0)
        // lat 47.9 with lon 8.0 stays on the west edge.
        assertThat(reader.altitudeAt(47.9, 8.0)).isWithin(1e-9).of(20.0)
    }

    @Test
    fun `returns null when any touched cell is a void`() {
        val reader = HgtReader.fromHgt(bigEndian(-32768, 100, 200, 300), size = 2)

        // The centre touches the north-west void.
        assertThat(reader.altitudeAt(47.5, 8.5)).isNull()
        // A query on the south row only touches non-void samples.
        assertThat(reader.altitudeAt(47.0, 8.5)).isWithin(1e-9).of(250.0)
    }

    @Test
    fun `returns null for out of range coordinates`() {
        val reader = HgtReader.fromHgt(bigEndian(0, 100, 200, 300), size = 2)

        assertThat(reader.altitudeAt(91.0, 8.0)).isNull()
        assertThat(reader.altitudeAt(47.0, 181.0)).isNull()
    }

    @Test
    fun `parses big endian int16 bytes with sign`() {
        val reader = HgtReader.fromHgt(bigEndian(-100, 100, 200, 300), size = 2)

        assertThat(reader.heights[0]).isEqualTo(-100)
        assertThat(reader.heights[1]).isEqualTo(100)
        assertThat(reader.altitudeAt(47.5, 8.5)).isWithin(1e-9).of(125.0)
    }

    @Test
    fun `supports the 3 arc-second 1201 grid`() {
        val reader = HgtReader(IntArray(1201 * 1201) { 100 }, size = 1201)
        assertThat(reader.altitudeAt(47.123, 8.456)).isWithin(1e-9).of(100.0)
    }

    @Test
    fun `supports the 1 arc-second 3601 grid`() {
        val reader = HgtReader(IntArray(3601 * 3601) { 100 }, size = 3601)
        assertThat(reader.altitudeAt(47.123, 8.456)).isWithin(1e-9).of(100.0)
    }

    @Test
    fun `rejects grids of the wrong size`() {
        assertThrows(IllegalArgumentException::class.java) { HgtReader(IntArray(4), size = 3) }
        assertThrows(IllegalArgumentException::class.java) {
            HgtReader.fromHgt(ByteArray(8), size = 3)
        }
    }
}
