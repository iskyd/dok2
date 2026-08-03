package dev.iskyd.dok2.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-marked waypoint (a spring, a junction, a campsite) tagged on a track, mirroring the
 * `waypoints` table from DOCUMENTATION.md §Data model. The notification action allows marking one
 * without unlocking the phone.
 */
@Entity(tableName = "waypoints")
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long? = null,
    @ColumnInfo(name = "t_ms") val tMs: Long,
    @ColumnInfo(name = "lat_e7") val latE7: Int,
    @ColumnInfo(name = "lon_e7") val lonE7: Int,
    val type: String? = null,
    val label: String? = null,
    @ColumnInfo(name = "photo_path") val photoPath: String? = null,
)
