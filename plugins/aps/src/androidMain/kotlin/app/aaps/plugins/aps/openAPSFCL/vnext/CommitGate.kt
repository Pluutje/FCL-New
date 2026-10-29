package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * The fixed limits of the "accel-first" commit trigger, in one place.
 *
 * The algorithm uses them in `accelFirstCommitTrigger`. The Analyser uses the same numbers to show
 * which limit held back the first commit of a meal (see `InsulinTimingHistory`). One place means the
 * two can not drift apart.
 */
object CommitGate {

    /** A commit needs at least this many mmol/L above target. */
    const val MIN_DELTA_TO_TARGET = 0.8

    /** Fast rise: the last 5 minutes rose at least this much (mmol/L)... */
    const val FAST_DELTA_5M = 0.08

    /** ...or the recent slope is at least this (mmol/L per hour). */
    const val FAST_SLOPE = 0.60

    /** The acceleration must be at least this... */
    const val ACCEL_MIN = 0.14

    /** ...or at least [ACCEL_MIN_LOW] together with a 5 minute rise of [ACCEL_MIN_LOW_DELTA_5M]. */
    const val ACCEL_MIN_LOW = 0.10
    const val ACCEL_MIN_LOW_DELTA_5M = 0.12

    /** True when the rise is fast enough. */
    fun fastLane(recentDelta5m: Double, recentSlope: Double): Boolean =
        recentDelta5m >= FAST_DELTA_5M || recentSlope >= FAST_SLOPE

    /** True when the acceleration is meaningful. */
    fun accelOk(acceleration: Double, recentDelta5m: Double): Boolean =
        acceleration >= ACCEL_MIN ||
            (acceleration >= ACCEL_MIN_LOW && recentDelta5m >= ACCEL_MIN_LOW_DELTA_5M)
}
