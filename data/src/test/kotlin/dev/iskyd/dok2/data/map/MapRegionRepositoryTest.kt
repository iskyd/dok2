package dev.iskyd.dok2.data.map

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.iskyd.dok2.data.prefs.SettingsRepository
import dev.iskyd.dok2.domain.map.PmtilesValidator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapRegionRepositoryTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `import writes a validated region byte-exact and records it in settings`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)
        val archive = archive(header = header(), payload = byteArrayOf(1, 2, 3, 4))

        assertImported(
            repository.import("alps.pmtiles", ByteArrayInputStream(archive)),
            "alps.pmtiles",
        )

        val stored = File(mapsDir, "alps.pmtiles")
        assertThat(stored.readBytes()).isEqualTo(archive)
        assertThat(repository.activeFileName()).isEqualTo("alps.pmtiles")
        assertThat(repository.activeFile()).isEqualTo(stored)
    }

    @Test
    fun `importing a replacement deletes the previous region file`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)

        assertImported(
            repository.import("alps.pmtiles", ByteArrayInputStream(archive())),
            "alps.pmtiles",
        )
        assertImported(
            repository.import("dolomites.pmtiles", ByteArrayInputStream(archive())),
            "dolomites.pmtiles",
        )

        assertThat(repository.activeFileName()).isEqualTo("dolomites.pmtiles")
        assertThat(File(mapsDir, "dolomites.pmtiles").exists()).isTrue()
        assertThat(File(mapsDir, "alps.pmtiles").exists()).isFalse()
        assertThat(mapsDir.listFiles().orEmpty()).hasLength(1)
    }

    @Test
    fun `activeFile returns null when the stored name points at a missing file`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)
        repository.import("alps.pmtiles", ByteArrayInputStream(archive()))

        File(mapsDir, "alps.pmtiles").delete()

        assertThat(repository.activeFileName()).isEqualTo("alps.pmtiles")
        assertThat(repository.activeFile()).isNull()
    }

    @Test
    fun `invalid header is rejected with nothing written`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)

        assertRejected(
            repository.import(
                "alps.pmtiles",
                ByteArrayInputStream(archive(header = header(tileType = 2))),
            )
        )

        assertThat(mapsDir.listFiles().orEmpty()).isEmpty()
        assertThat(repository.activeFileName()).isNull()
        assertThat(repository.activeFile()).isNull()
    }

    @Test
    fun `io error mid copy fails and leaves no partial file`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)

        assertFailed(
            repository.import("alps.pmtiles", ThrowingAfterPayloadInputStream(prefix = header()))
        )

        assertThat(mapsDir.listFiles().orEmpty()).isEmpty()
        assertThat(repository.activeFileName()).isNull()
    }

    @Test
    fun `hostile display names cannot escape the maps dir`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)

        assertRejected(repository.import("../evil.pmtiles", ByteArrayInputStream(archive())))
        assertImported(
            repository.import("my map file/…", ByteArrayInputStream(archive())),
            "mymapfile.pmtiles",
        )

        assertThat(mapsDir.listFiles().orEmpty()).hasLength(1)
        assertThat(File(mapsDir, "mymapfile.pmtiles").exists()).isTrue()
        assertThat(temporaryFolder.root.listFiles().orEmpty().none { it.name == "evil.pmtiles" })
            .isTrue()
    }

    @Test
    fun `missing or case-variant pmtiles suffix is normalized`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)

        assertImported(repository.import("alps", ByteArrayInputStream(archive())), "alps.pmtiles")
        assertThat(repository.activeFileName()).isEqualTo("alps.pmtiles")
        assertThat(File(mapsDir, "alps.pmtiles").exists()).isTrue()

        repository.remove()

        assertImported(
            repository.import("dolomites.PMTILES", ByteArrayInputStream(archive())),
            "dolomites.PMTILES",
        )
        assertThat(repository.activeFileName()).isEqualTo("dolomites.PMTILES")
        assertThat(File(mapsDir, "dolomites.PMTILES").exists()).isTrue()
    }

    @Test
    fun `remove deletes the region file and clears the setting`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)
        repository.import("alps.pmtiles", ByteArrayInputStream(archive()))

        repository.remove()

        assertThat(mapsDir.listFiles().orEmpty()).isEmpty()
        assertThat(repository.activeFileName()).isNull()
        assertThat(repository.activeFile()).isNull()
    }

    @Test
    fun `remove clears a stale setting pointing at a missing file`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)
        repository.import("alps.pmtiles", ByteArrayInputStream(archive()))
        File(mapsDir, "alps.pmtiles").delete()

        repository.remove()

        assertThat(repository.activeFileName()).isNull()
        assertThat(repository.activeFile()).isNull()
    }

    @Test
    fun `remove with no region is a no-op`() = runTest {
        val mapsDir = temporaryFolder.newFolder("maps")
        val repository = repository(mapsDir)

        repository.remove()

        assertThat(repository.activeFileName()).isNull()
        assertThat(mapsDir.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `uncreatable maps dir fails`() = runTest {
        val blocker = temporaryFolder.newFile("not-a-dir")
        val mapsDir = File(blocker, "maps")
        val repository = repository(mapsDir)

        assertFailed(repository.import("alps.pmtiles", ByteArrayInputStream(archive())))
    }

    private fun TestScope.repository(mapsDir: File): MapRegionRepository =
        MapRegionRepository(
            mapsDir = mapsDir,
            validator = PmtilesValidator(),
            settingsRepository = SettingsRepository(createDataStore()),
        )

    private fun TestScope.createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.newFile("test.preferences_pb")
        }

    private fun assertImported(result: MapRegionImportResult, fileName: String) {
        when (result) {
            is MapRegionImportResult.Imported -> assertThat(result.fileName).isEqualTo(fileName)
            is MapRegionImportResult.Rejected ->
                fail("expected Imported, got Rejected(${result.reason})")
            is MapRegionImportResult.Failed ->
                fail("expected Imported, got Failed(${result.error})")
        }
    }

    private fun assertRejected(result: MapRegionImportResult) {
        when (result) {
            is MapRegionImportResult.Imported ->
                fail("expected Rejected, got Imported(${result.fileName})")
            is MapRegionImportResult.Rejected -> assertThat(result.reason.trim()).isNotEmpty()
            is MapRegionImportResult.Failed ->
                fail("expected Rejected, got Failed(${result.error})")
        }
    }

    private fun assertFailed(result: MapRegionImportResult) {
        when (result) {
            is MapRegionImportResult.Imported ->
                fail("expected Failed, got Imported(${result.fileName})")
            is MapRegionImportResult.Rejected ->
                fail("expected Failed, got Rejected(${result.reason})")
            is MapRegionImportResult.Failed -> assertThat(result.error.trim()).isNotEmpty()
        }
    }

    /**
     * A complete PMTiles archive: a fixed 127-byte v3 header (magic "PMTiles" at bytes 0-6, spec
     * version at byte 7, tile type at byte 99, every other field zero) followed by [payload].
     */
    private fun archive(
        header: ByteArray = header(),
        payload: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): ByteArray = header + payload

    /** The fixed 127-byte PMTiles v3 header, [tileType] overridable to build invalid archives. */
    private fun header(tileType: Int = 1): ByteArray {
        val header = ByteArray(127)
        "PMTiles".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        header[7] = 3
        header[99] = tileType.toByte()
        return header
    }

    /**
     * Serves a valid [prefix] (the 127-byte header) followed by a few payload bytes, then throws
     * [IOException] on the next read — exercising the partial-copy cleanup.
     */
    private class ThrowingAfterPayloadInputStream(prefix: ByteArray) : InputStream() {
        private val totalBytes = prefix + ByteArray(32) { 0x01 }
        private var index = 0

        override fun read(): Int {
            if (index < totalBytes.size) return totalBytes[index++].toInt() and 0xFF
            throw IOException("disk read failed mid-copy")
        }
    }
}
