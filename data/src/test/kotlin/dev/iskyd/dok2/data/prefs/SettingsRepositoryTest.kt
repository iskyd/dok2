package dev.iskyd.dok2.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val activeMapFileKey = stringPreferencesKey("active_map_file_name")

    @Test
    fun `app settings pin the current defaults on a fresh datastore`() = runTest {
        val settings = SettingsRepository(createDataStore()).settingsFlow.first()

        assertThat(settings.privacyZoneEnabled).isFalse()
        assertThat(settings.privacyZoneRadiusM).isEqualTo(500f)
        assertThat(settings.stripExifOnExport).isTrue()
        assertThat(settings.elevationAscentMaxMps).isEqualTo(1.5f)
        assertThat(settings.elevationDescentMaxMps).isEqualTo(2.5f)
    }

    @Test
    fun `elevation bounds are read back after being set`() = runTest {
        val repository = SettingsRepository(createDataStore())

        repository.setElevationAscentMaxMps(2.0f)
        repository.setElevationDescentMaxMps(3.5f)

        assertThat(repository.settingsFlow.first().elevationAscentMaxMps).isEqualTo(2.0f)
        assertThat(repository.settingsFlow.first().elevationDescentMaxMps).isEqualTo(3.5f)
    }

    @Test
    fun `fresh datastore has no active map file`() = runTest {
        val settings = SettingsRepository(createDataStore()).settingsFlow.first()

        assertThat(settings.activeMapFileName).isNull()
    }

    @Test
    fun `setting an active map file is read back`() = runTest {
        val repository = SettingsRepository(createDataStore())

        repository.setActiveMapFileName("alps.pmtiles")

        assertThat(repository.settingsFlow.first().activeMapFileName).isEqualTo("alps.pmtiles")
    }

    @Test
    fun `setting null removes the active map file key`() = runTest {
        val dataStore = createDataStore()
        val repository = SettingsRepository(dataStore)

        repository.setActiveMapFileName("alps.pmtiles")
        repository.setActiveMapFileName(null)

        assertThat(repository.settingsFlow.first().activeMapFileName).isNull()
        assertThat(activeMapFileKey in dataStore.data.first()).isFalse()
    }

    @Test
    fun `active map file persists across repository instances on the same file`() = runTest {
        val file = temporaryFolder.newFile("test.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        SettingsRepository(firstStore).setActiveMapFileName("alps.pmtiles")
        // DataStore forbids two active instances per file, so the first store must be closed
        // (scope cancelled and the actor's cleanup awaited) before reopening the same file.
        firstScope.cancel()
        firstScope.coroutineContext[Job]!!.join()

        val reopened =
            SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

        assertThat(reopened.settingsFlow.first().activeMapFileName).isEqualTo("alps.pmtiles")
        assertThat(reopened.settingsFlow.first().stripExifOnExport).isTrue()
    }

    @Test
    fun `unreadable datastore file falls back to default settings`() = runTest {
        // A directory cannot be opened for reading: the repository catches the resulting
        // IOException and emits the defaults instead of propagating the error. The name must
        // still end in the factory's required `.preferences_pb` extension.
        val blocked = temporaryFolder.newFolder("blocked.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { blocked }

        val settings = SettingsRepository(dataStore).settingsFlow.first()

        assertThat(settings.privacyZoneEnabled).isFalse()
        assertThat(settings.activeMapFileName).isNull()
    }

    private fun TestScope.createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.newFile("test.preferences_pb")
        }
}
