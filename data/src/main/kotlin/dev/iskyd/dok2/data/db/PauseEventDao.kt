package dev.iskyd.dok2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data-access for the `pause_events` table. */
@Dao
interface PauseEventDao {

    /** Inserts pause/resume transitions in a single transaction. */
    @Insert suspend fun insertAll(events: List<PauseEventEntity>)

    /** Loads the transitions of a track in chronological order. */
    @Query("SELECT * FROM pause_events WHERE track_id = :trackId ORDER BY t_ms")
    suspend fun getByTrack(trackId: Long): List<PauseEventEntity>

    /** Deletes every transition of a track. */
    @Query("DELETE FROM pause_events WHERE track_id = :trackId")
    suspend fun deleteByTrack(trackId: Long)
}
