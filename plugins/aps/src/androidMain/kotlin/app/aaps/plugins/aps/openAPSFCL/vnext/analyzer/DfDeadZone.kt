package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import kotlin.math.abs
import kotlin.math.max

/**
 * Dead zone for what the auto learner writes (15/11/2026).
 *
 * The learner moves strength, timing and persistence in small steps of 1 point, up and down. Each step was written
 * at once. Now a small step is held back, and the step fades in between [ZERO_UNTIL_POINTS] and [FULL_FROM_POINTS]
 * points (no hard step).
 *
 * The weight is worked out from the three percentages (S, T, V). D, F and vExtra are then moved by that same weight,
 * so the percentages AND all 17 parameters (which come from the same D and F) stay in line.
 *
 * Only the write of the learner is changed. The dose calculation is not touched.
 */
object DfDeadZone {

    const val ZERO_UNTIL_POINTS = 1.0
    const val FULL_FROM_POINTS = 3.0

    data class Df(val d: Double, val f: Double, val vExtra: Double)

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** 0.0 for a change of [ZERO_UNTIL_POINTS] points or less, 1.0 from [FULL_FROM_POINTS] points. */
    fun weight(diffPoints: Double): Double =
        smooth01((diffPoints - ZERO_UNTIL_POINTS) / (FULL_FROM_POINTS - ZERO_UNTIL_POINTS))

    /** The biggest change of S, T or V in points between two states. */
    fun diffPoints(last: Df, new: Df, agg: AggLevels): Double {
        val a = DFMapping.toStvMap(last.d, last.f, 85.0, last.vExtra, agg = agg)
        val b = DFMapping.toStvMap(new.d, new.f, 85.0, new.vExtra, agg = agg)
        var biggest = 0.0
        for (key in listOf("sterkte", "timing", "volhoudendheid")) {
            biggest = max(biggest, abs((b[key] ?: 0) - (a[key] ?: 0)).toDouble())
        }
        return biggest
    }

    /** The values to write: [last] moved toward [new] by the weight. With no [last] the new values are used. */
    fun effective(last: Df?, new: Df, agg: AggLevels): Df {
        if (last == null) return new
        val w = weight(diffPoints(last, new, agg))
        return Df(
            d = last.d + (new.d - last.d) * w,
            f = last.f + (new.f - last.f) * w,
            vExtra = last.vExtra + (new.vExtra - last.vExtra) * w
        )
    }
}
