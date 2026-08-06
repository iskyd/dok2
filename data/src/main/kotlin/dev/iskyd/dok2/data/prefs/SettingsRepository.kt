package dev.iskyd.dok2.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The persisted user settings.
 *
 * @property privacyZoneEnabled whether exported tracks are trimmed within [privacyZoneRadiusM] of
 *   the start point (the home privacy zone). Off by default — the one privacy feature people
 *   actually need.
 * @property privacyZoneRadiusM the radius of the privacy zone in metres.
 * @property stripExifOnExport whether photo EXIF GPS data is stripped when exporting. On by
 *   default.
 * @property elevationAscentMaxMps the fastest plausible sustained climb, in m/s. Altitude steps
 *   faster than this are clamped before gain/loss is accumulated.
 * @property elevationDescentMaxMps the fastest plausible sustained descent, in m/s. Looser than the
 *   ascent bound because downhill running is genuinely faster.
 * @property activeMapFileName the file name of the active `.pmtiles` region in `filesDir/maps/`;
 *   null = none.
 */
data class AppSettings(
    val privacyZoneEnabled: Boolean = false,
    val privacyZoneRadiusM: Float = 500f,
    val stripExifOnExport: Boolean = true,
    val elevationAscentMaxMps: Float = 1.5f,
    val elevationDescentMaxMps: Float = 2.5f,
    val activeMapFileName: String? = null,
)

/**
 * Reads and writes [AppSettings] via DataStore Preferences.
 *
 * The [DataStore] instance is injected so this class holds no [Context] of its own; `:app` owns the
 * `Context.dataStore` delegate (one instance per preferences file name) and passes it in at wiring
 * time.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /** The current settings, re-emitted whenever any key changes. */
    val settingsFlow: Flow<AppSettings> =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { prefs ->
                AppSettings(
                    privacyZoneEnabled =
                        prefs[KEY_PRIVACY_ZONE_ENABLED] ?: DEFAULT_SETTINGS.privacyZoneEnabled,
                    privacyZoneRadiusM =
                        prefs[KEY_PRIVACY_ZONE_RADIUS_M] ?: DEFAULT_SETTINGS.privacyZoneRadiusM,
                    stripExifOnExport =
                        prefs[KEY_STRIP_EXIF_ON_EXPORT] ?: DEFAULT_SETTINGS.stripExifOnExport,
                    elevationAscentMaxMps =
                        prefs[KEY_ELEVATION_ASCENT_MAX_MPS]
                            ?: DEFAULT_SETTINGS.elevationAscentMaxMps,
                    elevationDescentMaxMps =
                        prefs[KEY_ELEVATION_DESCENT_MAX_MPS]
                            ?: DEFAULT_SETTINGS.elevationDescentMaxMps,
                    activeMapFileName =
                        prefs[KEY_ACTIVE_MAP_FILE_NAME] ?: DEFAULT_SETTINGS.activeMapFileName,
                )
            }

    /** Enables or disables the privacy zone. */
    suspend fun setPrivacyZoneEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PRIVACY_ZONE_ENABLED] = enabled }
    }

    /** Sets the privacy zone radius in metres. */
    suspend fun setPrivacyZoneRadiusM(radiusM: Float) {
        dataStore.edit { it[KEY_PRIVACY_ZONE_RADIUS_M] = radiusM }
    }

    /** Sets whether photo EXIF GPS data is stripped on export. */
    suspend fun setStripExifOnExport(strip: Boolean) {
        dataStore.edit { it[KEY_STRIP_EXIF_ON_EXPORT] = strip }
    }

    /** Sets the fastest plausible sustained climb, in m/s. */
    suspend fun setElevationAscentMaxMps(mps: Float) {
        dataStore.edit { it[KEY_ELEVATION_ASCENT_MAX_MPS] = mps }
    }

    /** Sets the fastest plausible sustained descent, in m/s. */
    suspend fun setElevationDescentMaxMps(mps: Float) {
        dataStore.edit { it[KEY_ELEVATION_DESCENT_MAX_MPS] = mps }
    }

    /**
     * Sets the active map region file name, or clears it when [name] is null. The key is removed
     * rather than written as null so a cleared setting is indistinguishable from an unset one.
     */
    suspend fun setActiveMapFileName(name: String?) {
        dataStore.edit { prefs ->
            if (name == null) prefs.remove(KEY_ACTIVE_MAP_FILE_NAME)
            else prefs[KEY_ACTIVE_MAP_FILE_NAME] = name
        }
    }

    private companion object {
        val KEY_PRIVACY_ZONE_ENABLED = booleanPreferencesKey("privacy_zone_enabled")
        val KEY_PRIVACY_ZONE_RADIUS_M = floatPreferencesKey("privacy_zone_radius_m")
        val KEY_STRIP_EXIF_ON_EXPORT = booleanPreferencesKey("strip_exif_on_export")
        val KEY_ELEVATION_ASCENT_MAX_MPS = floatPreferencesKey("elevation_ascent_max_mps")
        val KEY_ELEVATION_DESCENT_MAX_MPS = floatPreferencesKey("elevation_descent_max_mps")
        val KEY_ACTIVE_MAP_FILE_NAME = stringPreferencesKey("active_map_file_name")
        val DEFAULT_SETTINGS = AppSettings()
    }
}
