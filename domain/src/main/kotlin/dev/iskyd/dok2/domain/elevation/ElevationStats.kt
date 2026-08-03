package dev.iskyd.dok2.domain.elevation

/**
 * The 5 m hysteresis elevation accumulator from DOCUMENTATION.md §Elevation.
 *
 * Gain and loss accumulate only after a sustained 5 m move in one direction:
 * ```
 * if alt − ref >  5: gain += alt − ref; ref = alt
 * if ref − alt >  5: loss += ref − alt; ref = alt
 * ```
 *
 * **Why hysteresis.** GNSS vertical error is 2–3× horizontal. Without the threshold, sensor noise
 * alone produces hundreds of metres of phantom gain on flat ground. The trade-off is
 * under-reporting gentle rolling terrain; that is intentional.
 */
class ElevationStats(private val hysteresisM: Double = 5.0) {

    private var refM: Double? = null

    /** Accumulated gain in metres. */
    var gainM: Double = 0.0
        private set

    /** Accumulated loss in metres. */
    var lossM: Double = 0.0
        private set

    /** The current reference altitude, or null before the first [add]. */
    val referenceM: Double?
        get() = refM

    /**
     * Feeds one altitude sample. The reference is the last altitude that triggered a move (or the
     * first sample); small excursions are ignored.
     */
    fun add(altM: Double) {
        val reference = refM
        if (reference == null) {
            refM = altM
            return
        }
        val delta = altM - reference
        if (delta > hysteresisM) {
            gainM += delta
            refM = altM
        } else if (reference - altM > hysteresisM) {
            lossM += reference - altM
            refM = altM
        }
    }

    /** Resets the accumulator for a new track. */
    fun reset() {
        refM = null
        gainM = 0.0
        lossM = 0.0
    }
}
