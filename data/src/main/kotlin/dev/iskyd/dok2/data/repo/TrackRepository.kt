package dev.iskyd.dok2.data.repo

import androidx.room.withTransaction
import dev.iskyd.dok2.data.CoordinateCodec
import dev.iskyd.dok2.data.db.AppDatabase
import dev.iskyd.dok2.data.db.PauseEventDao
import dev.iskyd.dok2.data.db.PauseEventEntity
import dev.iskyd.dok2.data.db.TrackDao
import dev.iskyd.dok2.data.db.TrackEntity
import dev.iskyd.dok2.data.db.TrackPointDao
import dev.iskyd.dok2.data.db.TrackPointEntity
import dev.iskyd.dok2.data.db.TrackSummaryRow
import dev.iskyd.dok2.data.db.WaypointDao
import dev.iskyd.dok2.domain.model.PointState
import dev.iskyd.dok2.domain.model.Track
import dev.iskyd.dok2.domain.model.TrackPoint
import dev.iskyd.dok2.domain.model.TrackSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single entry point for track persistence.
 *
 * Coordinates are converted between the domain layer's `Double` degrees and the storage layer's
 * `Int` 1e-7 degrees here and nowhere else (AGENTS.md, DOCUMENTATION.md). Pause/resume state is
 * mapped between [PointState] and the integer `state` column at the same boundary.
 */
class TrackRepository(
    private val database: AppDatabase,
    private val trackDao: TrackDao,
    private val trackPointDao: TrackPointDao,
    private val pauseEventDao: PauseEventDao,
    private val waypointDao: WaypointDao,
) {

    /**
     * Creates a new track and returns its id. The track starts open (`ended_at` null); it is
     * finalised later via [finalizeTrack].
     */
    suspend fun startTrack(startedAtMs: Long, name: String?): Long =
        trackDao.insert(TrackEntity(name = name, startedAtMs = startedAtMs))

    /**
     * Stores a batch of points for a track in one transaction, assigning each point its `seq` from
     * the highest stored value. The caller batches roughly every 10 points (30 s of recording); a
     * batch either lands whole or not at all.
     */
    suspend fun appendPoints(trackId: Long, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        database.withTransaction {
            var nextSeq = trackPointDao.maxSeq(trackId) + 1
            val entities =
                points.map { point ->
                    TrackPointEntity(
                        trackId = trackId,
                        seq = nextSeq++,
                        tMs = point.tMs,
                        latE7 = CoordinateCodec.toE7(point.latDeg),
                        lonE7 = CoordinateCodec.toE7(point.lonDeg),
                        accuracyM = point.accuracyM,
                        altGnssM = point.altGnssM,
                        pressureHpa = point.pressureHpa,
                        speedMps = point.speedMps,
                        bearingDeg = point.bearingDeg,
                        state = point.state.code,
                        accumulated = point.accumulated,
                    )
                }
            trackPointDao.insertAll(entities)
        }
    }

    /** Appends pause/resume transitions, each an (epoch millis, new state) pair. */
    suspend fun appendPauseEvents(trackId: Long, events: List<Pair<Long, PointState>>) {
        if (events.isEmpty()) return
        pauseEventDao.insertAll(
            events.map { (tMs, newState) ->
                PauseEventEntity(trackId = trackId, tMs = tMs, newState = newState.code)
            }
        )
    }

    /**
     * Writes the final summary figures for a finished track. Raw per-point data is never touched;
     * these are the aggregated, derived values.
     */
    suspend fun finalizeTrack(
        trackId: Long,
        endedAtMs: Long,
        distanceM: Double,
        movingTimeS: Long,
        elapsedTimeS: Long,
        gainBaroM: Double?,
        lossBaroM: Double?,
        gainDemM: Double?,
        lossDemM: Double?,
        seaLevelHpa: Double?,
        calibrated: Boolean,
    ) {
        val entity = trackDao.getById(trackId) ?: return
        trackDao.update(
            entity.copy(
                endedAtMs = endedAtMs,
                distanceM = distanceM,
                movingTimeS = movingTimeS,
                elapsedTimeS = elapsedTimeS,
                gainBaroM = gainBaroM,
                lossBaroM = lossBaroM,
                gainDemM = gainDemM,
                lossDemM = lossDemM,
                seaLevelHpa = seaLevelHpa,
                calibrated = calibrated,
            )
        )
    }

    /** Renames a track, or clears its name when null; a no-op when the track does not exist. */
    suspend fun setTrackName(trackId: Long, name: String?) {
        val entity = trackDao.getById(trackId) ?: return
        trackDao.update(entity.copy(name = name))
    }

    /**
     * Replaces a track's notes, or clears them when null; a no-op when the track does not exist.
     */
    suspend fun setTrackNotes(trackId: Long, notes: String?) {
        val entity = trackDao.getById(trackId) ?: return
        trackDao.update(entity.copy(notes = notes))
    }

    /**
     * Writes the DEM-derived altitude for a set of points, keyed by `seq`, in one transaction. This
     * runs at track-save time (DOCUMENTATION.md §Elevation — Final); raw columns are never touched.
     */
    suspend fun updateDemAltitudes(trackId: Long, altDemBySeq: Map<Long, Double?>) {
        if (altDemBySeq.isEmpty()) return
        database.withTransaction {
            for ((seq, altDemM) in altDemBySeq) {
                trackPointDao.updateAltDem(trackId, seq, altDemM)
            }
        }
    }

    /** Deletes a track and its points, pause events and waypoints in one transaction. */
    suspend fun deleteTrack(trackId: Long) {
        database.withTransaction {
            trackPointDao.deleteByTrack(trackId)
            pauseEventDao.deleteByTrack(trackId)
            waypointDao.deleteByTrack(trackId)
            trackDao.getById(trackId)?.let { trackDao.delete(it) }
        }
    }

    /**
     * Emits the track list, newest first, as the lightweight summary projection used by the list UI
     * (which never instantiates a map view).
     */
    fun observeTrackSummaries(): Flow<List<TrackSummary>> =
        trackDao.observeSummaries().map { rows -> rows.map { it.toSummary() } }

    /** Loads a full track by id, or null when it does not exist. */
    suspend fun getTrack(trackId: Long): Track? = trackDao.getById(trackId)?.toDomain()

    /**
     * The single track that was never finalised, if any. On launch the UI prompts to resume or
     * finalise it; the boot receiver resumes recording into it (DOCUMENTATION.md mitigation 5).
     */
    suspend fun getOpenTrack(): Track? = trackDao.getOpenTrack()?.toDomain()

    /** Loads all points of a track in recording order, converted to decimal degrees. */
    suspend fun getPoints(trackId: Long): List<TrackPoint> =
        trackPointDao.getByTrack(trackId).map { it.toDomain() }

    /** Loads the pause/resume transitions of a track in chronological order. */
    suspend fun getPauseEvents(trackId: Long): List<Pair<Long, PointState>> =
        pauseEventDao.getByTrack(trackId).map { it.tMs to pointStateFromCode(it.newState) }
}

private fun TrackSummaryRow.toSummary(): TrackSummary =
    TrackSummary(
        id = id,
        name = name,
        startedAtMs = startedAtMs,
        distanceM = distanceM,
        elapsedTimeS = elapsedTimeS,
        gainDemM = gainDemM,
    )

private fun TrackEntity.toDomain(): Track =
    Track(
        id = id,
        name = name,
        activityType = activityType,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        distanceM = distanceM,
        movingTimeS = movingTimeS,
        elapsedTimeS = elapsedTimeS,
        gainBaroM = gainBaroM,
        lossBaroM = lossBaroM,
        gainDemM = gainDemM,
        lossDemM = lossDemM,
        seaLevelHpa = seaLevelHpa,
        calibrated = calibrated,
        thumbnailPath = thumbnailPath,
        notes = notes,
    )

private fun TrackPointEntity.toDomain(): TrackPoint =
    TrackPoint(
        tMs = tMs,
        latDeg = CoordinateCodec.toDegrees(latE7),
        lonDeg = CoordinateCodec.toDegrees(lonE7),
        accuracyM = accuracyM,
        altGnssM = altGnssM,
        pressureHpa = pressureHpa,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        state = pointStateFromCode(state),
        accumulated = accumulated,
    )

private fun pointStateFromCode(code: Int): PointState = PointState.entries.first { it.code == code }
