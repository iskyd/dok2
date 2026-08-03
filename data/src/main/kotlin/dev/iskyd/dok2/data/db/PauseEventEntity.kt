package dev.iskyd.dok2.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A pause/resume transition written during recording, so a track is exactly reconstructable from
 * the database (DOCUMENTATION.md §Recording state machine). Every transition is stored; no
 * intermediate state is derivable only from the points.
 */
@Entity(tableName = "pause_events", primaryKeys = ["track_id", "t_ms"])
data class PauseEventEntity(
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "t_ms") val tMs: Long,
    @ColumnInfo(name = "new_state") val newState: Int,
)
