package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * The fixed limits of the "accel-first" commit trigger, in one place.
 *
 * The algorithm uses them in `accelFirstCommitTrigger`. The Analyser uses the same numbers to show
 * which limit held back the first commit of a meal (see `InsulinTimingHistory`). One place means the
 * two can not drift apart.
 */
object CommitGate {

    /**
     * Below this many mmol/L above target there is no commit.
     * From here the commit grows smoothly until [DELTA_RAMP_END] (see [deltaWeight]).
     * Before v152 this was one hard limit of 0.8: a BG of 0.7 gave no commit and 0.8 a full one,
     * so 0.1 mmol/L could cost one cycle (5 minutes).
     */
    const val DELTA_RAMP_START = 0.5

    /** From this many mmol/L above target the commit has its full size. */
    const val DELTA_RAMP_END = 0.8

    /**
     * Weight (0..1) of the commit for a BG [deltaToTarget] mmol/L above target.
     * 0 at [DELTA_RAMP_START] or lower, 1 at [DELTA_RAMP_END] or higher, a smooth S-curve in between.
     */
    fun deltaWeight(deltaToTarget: Double): Double {
        val x = ((deltaToTarget - DELTA_RAMP_START) / (DELTA_RAMP_END - DELTA_RAMP_START)).coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

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
