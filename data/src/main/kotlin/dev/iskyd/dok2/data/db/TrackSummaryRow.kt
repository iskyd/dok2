package dev.iskyd.dok2.data.db

/**
 * The row of the track-list summary projection produced by [TrackDao.observeSummaries]. It is
 * deliberately limited to the columns the track list renders and is mapped to
 * `dev.iskyd.dok2.domain.model.TrackSummary` in the repository layer.
 */
data class TrackSummaryRow(
    val id: Long,
    val name: String?,
    val startedAtMs: Long,
    val distanceM: Double,
    val elapsedTimeS: Long,
    val gainDemM: Double?,
)
