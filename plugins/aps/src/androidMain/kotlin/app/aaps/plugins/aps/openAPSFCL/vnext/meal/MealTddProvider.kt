package app.aaps.plugins.aps.openAPSFCL.vnext.meal

import app.aaps.core.interfaces.stats.TddCalculator
import org.joda.time.DateTime
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Cached Average TDD provider for meal-scaling.
 *
 * - Reads TDD from AAPS via TddCalculator
 * - Caches the computed average for a fixed interval (default: 60 min)
 */
@SingleIn(AppScope::class)
class MealTddProvider @Inject constructor(
    private val tddCalculator: TddCalculator
) {
    private var cachedAvgTdd: Double = 0.0
    private var lastUpdateMs: Long = 0L

    // Kies hier je periode (5 is meestal beter dan 7 voor maaltijd-schaal)
    private val days: Long = 5

    // 1× per uur refreshen
    private val cacheMs: Long = 60 * 60 * 1000L

    fun getAverageTdd(now: DateTime): Double {
        val nowMs = now.millis
        val isExpired = (nowMs - lastUpdateMs) >= cacheMs

        if (cachedAvgTdd <= 0.0 || isExpired) {
            val tdds = kotlinx.coroutines.runBlocking {
                tddCalculator.calculate(days, allowMissingDays = true)
            }
            val avg = tddCalculator.averageTDD(tdds)
            cachedAvgTdd = avg?.data?.totalAmount ?: 0.0
            lastUpdateMs = nowMs
        }

        return cachedAvgTdd
    }
}
