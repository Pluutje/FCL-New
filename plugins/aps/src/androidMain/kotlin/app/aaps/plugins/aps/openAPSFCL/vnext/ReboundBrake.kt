package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * Experiment "rebound brake" (12/11/2026): a part of the dose is held back in the first hour after a low BG, while the BG
 * rises again.
 *
 * WHY: after a low BG the rise is often fast, because the user eats or drinks sugar to treat the low. The algorithm
 * sees a fast rise and treats it as a meal. In the logs of two users (26 such rises, 8 days) the BG fell below 4.0
 * again in 7 cases, also after small doses. The old post-hypo brake does not help enough: it only arms at a lowest BG of
 * 4.0 or lower (a hard step), and it lets nearly all insulin through when the rise is big and fast.
 *
 * HOW (all weights are gradual, there is no hard step):
 *  - Low severity: 0 when the lowest BG of the last [AGE_ZERO_MIN] minutes is [LOW_FROM] or higher, 1 at [LOW_TO] or lower.
 *    It fades out with the age of that lowest BG: full until [AGE_FULL_MIN] minutes, zero at [AGE_ZERO_MIN] minutes.
 *  - BG fade: the brake fades out when the BG is already high, from [BG_FADE_FROM] to [BG_FADE_TO] mmol/L. A high BG
 *    needs insulin, also after a low.
 *  - Cut = maxCut(strength) x severity x BG fade x (1 - part that the post-hypo brake already cut). So the two brakes
 *    do not count the same cut twice, and this one is full when the old brake let everything through.
 *  - maxCut is [MAX_CUT_AT_REFERENCE] (50 percent) at strength 75 percent, linear in the strength, never above [MAX_CUT].
 *
 * OVERRIDE (12/11/2026): with a positive override ("I eat", often a fatty meal) the cut is scaled with the brake
 * strength of [OverrideBrakeScale], the same rule as for the post-hypo brake. Then it only holds back after a deep,
 * recent low that has not come back yet. Without a positive override nothing changes. See [withOverride].
 *
 * LIMITS: strength 0 is off (the default). It only makes a dose smaller, never bigger. It does not change any other
 * brake. Hypo protection stays as it is.
 *
 * Backtest (CSV 1 to 12 November 2026, 26 rises after a low): at strength 75 percent it holds back about 35 to 49
 * percent of the insulin of such a rise, and about 2.2 U per day (6 to 8 percent of all insulin). At 40 percent about
 * 1.2 U per day.
 *
 * The cut is stored for the card in the tab Experimenteel. The calculation is in pure functions, easy to test.
 */
object ReboundBrake {

    /** Lowest BG (mmol/L) from which the severity starts. */
    const val LOW_FROM = 5.0

    /** Lowest BG (mmol/L) at which the severity is full. */
    const val LOW_TO = 4.0

    /** Minutes since the lowest BG until which the severity stays full. */
    const val AGE_FULL_MIN = 45.0

    /** Minutes since the lowest BG at which the severity is zero. Also the look-back time. */
    const val AGE_ZERO_MIN = 90.0

    /** From this BG (mmol/L) the brake starts to fade out. */
    const val BG_FADE_FROM = 9.0

    /** At this BG (mmol/L) and higher the brake is gone. */
    const val BG_FADE_TO = 12.0

    /** The strength (as a factor) at which the maximum cut is [MAX_CUT_AT_REFERENCE]. */
    const val REFERENCE_STRENGTH = 0.75

    /** Maximum part of the dose that is held back at the reference strength and full severity. */
    const val MAX_CUT_AT_REFERENCE = 0.5

    /** The part of the dose that is held back is never higher than this, also at the highest strength. */
    const val MAX_CUT = 0.8

    /** A cut smaller than this (U) is not stored. */
    private const val MIN_STORE_U = 0.02

    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 6000
    private const val PREFS = "fcl_rebound_brake"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"

    data class Event(
        val timestampMs: Long,
        /** Insulin (U) that was held back in this cycle. */
        val cutU: Double,
        /** Lowest BG (mmol/L) of the last [AGE_ZERO_MIN] minutes. */
        val lowestBg: Double
    )

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** Maximum part of the dose that is held back: 0 to [MAX_CUT]. [strength] is the factor 0 to 1.5. */
    fun maxCut(strength: Double): Double =
        (MAX_CUT_AT_REFERENCE * strength.coerceAtLeast(0.0) / REFERENCE_STRENGTH).coerceIn(0.0, MAX_CUT)

    /** 0 to 1: how deep and how recent the lowest BG is. */
    fun lowSeverity(lowestBg: Double, minutesSinceLowest: Double): Double {
        val depth = smooth01((LOW_FROM - lowestBg) / (LOW_FROM - LOW_TO))
        val recent = 1.0 - smooth01((minutesSinceLowest - AGE_FULL_MIN) / (AGE_ZERO_MIN - AGE_FULL_MIN))
        return depth * recent
    }

    /** 1 up to [BG_FADE_FROM], then smoothly down to 0 at [BG_FADE_TO]. */
    fun bgFade(bg: Double): Double = 1.0 - smooth01((bg - BG_FADE_FROM) / (BG_FADE_TO - BG_FADE_FROM))

    /**
     * Factor for the dose: 1.0 = no change, lower = a part is held back.
     * [alreadyCutFraction] is the part (0 to 1) that the post-hypo brake already cut in this cycle.
     */
    fun factor(
        strength: Double,
        lowestBg: Double,
        minutesSinceLowest: Double,
        bg: Double,
        alreadyCutFraction: Double
    ): Double {
        val cut = maxCut(strength) * lowSeverity(lowestBg, minutesSinceLowest) * bgFade(bg) *
            (1.0 - alreadyCutFraction.coerceIn(0.0, 1.0))
        return 1.0 - cut
    }

    /**
     * Applies the override rule. [overrideStrength] is the brake strength of [OverrideBrakeScale]: 1.0 without a
     * positive override (no change), and with a positive override the severity of the low (0 to 1, only a deep, recent
     * low that has not recovered). A positive override means "I eat", so the brake then holds back little or nothing.
     */
    fun withOverride(factor: Double, overrideStrength: Double): Double =
        1.0 - (1.0 - factor) * overrideStrength.coerceIn(0.0, 1.0)

    /**
     * The lowest BG in the last [AGE_ZERO_MIN] minutes and how many minutes ago it was, or null without readings.
     * [history] is a list of (time in ms, BG in mmol/L). With equal values the most recent one counts.
     */
    fun lowestInWindow(history: List<Pair<Long, Double>>, nowMs: Long): Pair<Double, Double>? {
        var best: Pair<Long, Double>? = null
        for (p in history) {
            val ageMin = (nowMs - p.first) / 60_000.0
            if (ageMin < 0.0 || ageMin > AGE_ZERO_MIN) continue
            val b = best
            if (b == null || p.second < b.second || (p.second == b.second && p.first > b.first)) best = p
        }
        val found = best ?: return null
        return found.second to (nowMs - found.first) / 60_000.0
    }

    fun record(context: Context, nowMs: Long, cutU: Double, lowestBg: Double) {
        if (cutU >= MIN_STORE_U) addEvent(context, Event(nowMs, cutU, lowestBg))
    }

    fun loadEvents(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size != 3) return@mapNotNull null
            try {
                Event(p[0].toLong(), p[1].toDouble(), p[2].toDouble())
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    private fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") { "${it.timestampMs}$SEP${it.cutU}$SEP${it.lowestBg}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_EVENTS, text).apply()
    }

    /** The event closest to [timestampMs] within [toleranceMs]. */
    fun nearest(sorted: List<Event>, timestampMs: Long, toleranceMs: Long): Event? {
        var best: Event? = null
        var bestD = Long.MAX_VALUE
        for (e in sorted) {
            val d = abs(e.timestampMs - timestampMs)
            if (d <= toleranceMs && d < bestD) { best = e; bestD = d }
            if (e.timestampMs - timestampMs > toleranceMs) break
        }
        return best
    }
}
