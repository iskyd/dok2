package dev.iskyd.dok2.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A recorded track row, mirroring the `tracks` table from DOCUMENTATION.md §Data model.
 *
 * Barometric and DEM gain/loss figures are stored separately so the DEM-derived values can be
 * recomputed without touching raw sensor data. [calibrated] records whether the barometer was
 * calibrated against GNSS altitudes during the first 60 seconds.
 *
 * The Kotlin defaults on the NOT NULL columns (activity type, distance, moving/elapsed time,
 * calibrated) are reflected as SQL `DEFAULT` clauses in the exported schema.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String? = null,
    @ColumnInfo(name = "activity_type") val activityType: String = "hike",
    @ColumnInfo(name = "started_at") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at") val endedAtMs: Long? = null,
    @ColumnInfo(name = "distance_m") val distanceM: Double = 0.0,
    @ColumnInfo(name = "moving_time_s") val movingTimeS: Long = 0,
    @ColumnInfo(name = "elapsed_time_s") val elapsedTimeS: Long = 0,
    @ColumnInfo(name = "gain_baro_m") val gainBaroM: Double? = null,
    @ColumnInfo(name = "loss_baro_m") val lossBaroM: Double? = null,
    @ColumnInfo(name = "gain_dem_m") val gainDemM: Double? = null,
    @ColumnInfo(name = "loss_dem_m") val lossDemM: Double? = null,
    @ColumnInfo(name = "sea_level_hpa") val seaLevelHpa: Double? = null,
    val calibrated: Boolean = false,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    val notes: String? = null,
)
