package dev.iskyd.dok2.domain.model

/** Lowest and highest altitude of a track in metres, or null when no point has an altitude. */
data class ElevationExtremes(val minM: Double?, val maxM: Double?)
