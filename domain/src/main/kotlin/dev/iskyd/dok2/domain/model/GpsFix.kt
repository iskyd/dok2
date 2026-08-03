package dev.iskyd.dok2.domain.model

/**
 * A raw position as reported by the GNSS receiver, before any filtering.
 *
 * This is the input to [dev.iskyd.dok2.domain.filter.FilterChain] and the shape the JSONL replay
 * fixtures deserialize into. Raw sensor values ([altGnssM], [pressureHpa]) pass through the filter
 * chain untouched — they are never overwritten or derived.
 *
 * Timestamps always come from the fix, never from the clock at callback time (DOCUMENTATION.md,
 * Location acquisition).
 */
data class GpsFix(
    val tMs: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val accuracyM: Double,
    val altGnssM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val pressureHpa: Double? = null,
)
