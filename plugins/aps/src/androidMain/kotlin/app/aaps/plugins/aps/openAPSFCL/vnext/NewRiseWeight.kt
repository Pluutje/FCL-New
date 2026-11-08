package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * Weight from 0 to 1 for "this is a NEW rise, not the tail of the old meal".
 *
 * An open meal episode can last for hours. Some brakes were made for the tail of a meal (late phase,
 * after an early commit). They must fade out when the BG clearly starts to rise again from a low point.
 *
 * The weight is the product of two smooth parts:
 * - how far the BG is above its lowest value of the last [WINDOW_MIN] minutes (full at [RISE_FULL_MMOL]),
 * - how fast the BG rises now (starts at [SLOPE_START] mmol/L/h, full at [SLOPE_FULL]).
 *
 * There is no on/off step. Pure function, easy to test.
 */
object NewRiseWeight {

    const val WINDOW_MIN = 60
    const val RISE_FULL_MMOL = 0.8
    const val SLOPE_START = 0.5
    const val SLOPE_FULL = 1.5

    /**
     * @param bgNow BG now (mmol/L)
     * @param recentBg BG values of the last [WINDOW_MIN] minutes (mmol/L), including now is fine
     * @param recentSlope the recent slope in mmol/L/h
     */
    fun calculate(bgNow: Double, recentBg: List<Double>, recentSlope: Double): Double {
        if (recentBg.isEmpty()) return 0.0
        val rise = bgNow - recentBg.min()
        val riseWeight = smooth01(rise / RISE_FULL_MMOL)
        val slopeWeight = smooth01((recentSlope - SLOPE_START) / (SLOPE_FULL - SLOPE_START))
        return riseWeight * slopeWeight
    }

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
