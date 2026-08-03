package dev.iskyd.dok2.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's Room database.
 *
 * WAL journal mode is mandated by DOCUMENTATION.md: a process kill during a batch insert then costs
 * at most the un-committed transaction, never the whole file. Points are batched every 30 s, which
 * bounds the loss window.
 */
@Database(
    entities =
        [
            TrackEntity::class,
            TrackPointEntity::class,
            PauseEventEntity::class,
            WaypointEntity::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    /** Data-access for the `tracks` table. */
    abstract fun trackDao(): TrackDao

    /** Data-access for the `trackpoints` table. */
    abstract fun trackPointDao(): TrackPointDao

    /** Data-access for the `pause_events` table. */
    abstract fun pauseEventDao(): PauseEventDao

    /** Data-access for the `waypoints` table. */
    abstract fun waypointDao(): WaypointDao

    companion object {
        /** The on-device database file name. */
        const val NAME = "dok2.db"

        /**
         * Opens (creating on first use) the app database in WAL mode. Construction happens in
         * `:app`; keeping the factory here makes the WAL mandate impossible to forget.
         */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
