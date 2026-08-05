package dev.iskyd.dok2

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dev.iskyd.dok2.data.db.AppDatabase
import dev.iskyd.dok2.data.dem.DemTileRepository
import dev.iskyd.dok2.data.map.MapRegionRepository
import dev.iskyd.dok2.data.prefs.SettingsRepository
import dev.iskyd.dok2.data.repo.TrackRepository
import dev.iskyd.dok2.domain.map.PmtilesValidator
import java.io.File

/**
 * The process-scoped manual constructor injection graph. There is deliberately no DI framework:
 * every dependency is wired here in [onCreate] and passed down explicitly.
 *
 * The single `settings` DataStore instance lives here (the `:data` module takes the [DataStore] as
 * a parameter rather than declaring its own delegate, so the file name is owned in exactly one
 * place).
 */
private val Context.dataStore by preferencesDataStore(name = "settings")

class Dok2Application : Application() {

    lateinit var database: AppDatabase

    lateinit var trackRepository: TrackRepository

    lateinit var settingsRepository: SettingsRepository

    lateinit var demTileRepository: DemTileRepository

    lateinit var mapRegionRepository: MapRegionRepository

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.create(this)
        trackRepository =
            TrackRepository(
                database,
                database.trackDao(),
                database.trackPointDao(),
                database.pauseEventDao(),
                database.waypointDao(),
            )
        settingsRepository = SettingsRepository(dataStore)
        demTileRepository = DemTileRepository(this)
        mapRegionRepository =
            MapRegionRepository(File(filesDir, "maps"), PmtilesValidator(), settingsRepository)
    }
}
