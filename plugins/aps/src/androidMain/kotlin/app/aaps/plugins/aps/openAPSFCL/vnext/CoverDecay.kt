package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context
import kotlin.math.abs

/**
 * Experiment "cover decay" (10/11/2026): the decay of the doses after the first commit follows the IOB cover.
 *
 * WHY: the late-commit decay counts commits. After the third commit it is at its floor, also when the commits were
 * small and the BG still rises. Measured in the logs of three users (7 to 9 days each), that decay does not tell a rise
 * that goes on from a rise that turns (0.49 to 0.55, where 0.5 is chance and 1.0 is perfect). The cover below does a
 * little better (0.59 to 0.66 for the two users with a personal reference).
 *
 * HOW:
 *  - Need (mmol/L) = BG above target + the rise that is expected in the next [AHEAD_HOURS].
 *  - IOB per need (U per mmol/L) = IOB / need. This is written to the CSV for every cycle (`cover_decay_iob_per_need`).
 *  - Relative cover = IOB per need / personal reference. The reference is the insulin this user needs per mmol/L of
 *    rise, the same one as in [InsulinBudget]. 1.0 means: the IOB is what this user needs for the BG that is left.
 *  - Decay = 1.0 up to a relative cover of [START], then smoothly down to [FLOOR] at [START] + [SPAN].
 *  - The strength blends the old decay and this one. At strength 1.0 (100 percent) the blend holds [WEIGHT_AT_100] of
 *    this decay, at the highest strength (1.5) one and a half times that. It never replaces the old decay fully.
 *    WHY so little: the old decay is low (about 0.24 at the third commit), so in the first test (10-11/11/2026) a
 *    weight of 0.2 already made the second and third dose of a meal about one and a half times as big.
 *  - The decay is only made weaker (bigger doses) while the BG rises: that part fades out with [riseWeight].
 *    With a flat or falling BG the experiment can only make the decay stronger.
 *
 * STILL NEEDS INSULIN (added 15/11/2026): a long, slow rise (for example a fatty meal) looks like a rise that turns: the
 * rise slows down, the commit count is high, and the decay is at its floor, while the BG is still far above target and
 * the IOB covers only half of what this user needs. Two changes, both only while the BG is well above target, still
 * rises, and the relative cover is low ([needsWeight], all ramps smooth):
 *  - a floor under the decay: [FLOOR_SHARE] x the decay from the cover x the needs weight x the strength (up to 1.0);
 *  - a smaller "turn" (the post-peak cut in the commit decay is scaled down by [omslagScale]).
 * Both fade out to nothing when the cover is high, the BG is near target, or the BG does not rise.
 *
 * STEP BRAKE (added 16/11/2026): the step decay brake (`step_decel_brake`) cuts the dose when the last 5-minute step of the
 * rise is small against the biggest step of this rise. After a fast start this happens also in a long, slow rise (16/11
 * 13:22-13:47: brake 0.40 for 6 cycles, while the BG still rose from 8.7 to 10.6 and the IOB cover was low). The same
 * "still needs insulin" weight lets this brake fade out: [stepBrakeRelease]. It acts in the middle of a rise, after the
 * first doses, not at the start (the brake is 1.0 there) and not at the peak (the rise weight goes to 0 when the rise stops).
 *
 * LIMITS: strength 0 is off (the default). It does nothing without a personal reference, for the first commit of a
 * meal, and at night (it fades with the day weight). It only changes the late-commit decay; all hypo protection and
 * all other brakes stay as they are. There are no hard steps inside the calculation.
 *
 * [START], [SPAN] and [FLOOR] are a first choice. They must be checked against the logged values before the strength
 * goes up.
 *
 * Cycles in which the experiment changed the decay are stored and written to the CSV:
 * `cover_decay_old_mul`, `cover_decay_new_mul`, `cover_decay_rel_cover`, `cover_decay_delta_u`.
 * The last one is an estimate of the insulin that the experiment added (plus) or held back (minus), see [commitDeltaU].
 *
 * The calculation is in pure functions, easy to test.
 */
object CoverDecay {

    /** The expected rise is the slope times this many hours. */
    const val AHEAD_HOURS = 0.5

    /** Upper limit of the slope (mmol/L per hour) that is used for the expected rise. */
    const val AHEAD_MAX_SLOPE = 6.0

    /** Lower limit of the BG that needs insulin (mmol/L), so that the cover stays finite near target. */
    const val MIN_NEED_MMOL = 0.5

    /** Up to this relative cover there is no decay. */
    const val START = 0.2

    /** Length of the ramp: at [START] + [SPAN] the decay is at [FLOOR]. */
    const val SPAN = 0.8

    /** Lowest value of the decay. */
    const val FLOOR = 0.10

    /** Part of this decay in the blend at strength 1.0 (100 percent). See the kdoc of this object. */
    const val WEIGHT_AT_100 = 0.20

    /** From this slope (mmol/L per hour) a weaker decay counts in full. At 0 or lower it does not count. */
    const val RISE_FULL_SLOPE = 1.0

    /** The BG must be this far above target (mmol/L) before "still needs insulin" starts, and this far for the full effect. */
    const val NEEDS_BG_START = 2.0
    const val NEEDS_BG_SPAN = 2.0

    /** Up to this relative cover "still needs insulin" counts in full. It fades out over [NEEDS_COVER_SPAN]. */
    const val NEEDS_COVER_START = 0.6
    const val NEEDS_COVER_SPAN = 0.3

    /** Part of the decay from the cover that is used as a floor under the late-commit decay (first choice, careful). */
    const val FLOOR_SHARE = 0.5

    /** A change of the decay smaller than this is not stored. */
    private const val MIN_CHANGE = 0.01

    private const val KEEP_DAYS = 60L
    private const val MAX_EVENTS = 6000
    private const val PREFS = "fcl_cover_decay"
    private const val KEY_EVENTS = "events_v1"
    private const val SEP = "|"
    const val CSV_TOLERANCE_MS = 150_000L

    data class Event(
        val timestampMs: Long,
        /** The late-commit decay before the experiment. */
        val oldMul: Double,
        /** The late-commit decay after the blend. */
        val newMul: Double,
        val relativeCover: Double,
        /** Estimate of the extra insulin (U) in this cycle. Below zero = less insulin. 0 when no dose was changed. */
        val deltaU: Double = 0.0,
        /** "Still needs insulin" (0 to 1), see [needsWeight]. */
        val needsWeight: Double = 0.0,
        /** The floor under the decay that this cycle used, see [floorMul]. 0 = no floor. */
        val floorMul: Double = 0.0,
        /** Scale of the post-peak cut of the commit decay, see [omslagScale]. 1 = no change. */
        val omslagScale: Double = 1.0
    )

    data class Result(
        /** The decay to use: the blend of the old decay and [decayMul]. */
        val blendedMul: Double,
        /** The decay from the cover alone. NaN when the experiment does nothing. */
        val decayMul: Double,
        /** NaN when the experiment does nothing. */
        val relativeCover: Double,
        /** How much of [decayMul] is in the blend, 0 to 1. */
        val weight: Double,
        /** How much "still needs insulin" counts in this cycle, 0 to 1. 0 when the experiment does nothing. */
        val needsWeight: Double = 0.0,
        /** The floor under the decay in this cycle (0 = none). */
        val floorMul: Double = 0.0
    )

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * Expected rise (mmol/L) in the next [AHEAD_HOURS].
     * The lower of the two slopes is used: both must show a rise. One jump in the last reading does not count.
     */
    fun aheadMmol(slope: Double, recentSlope: Double): Double =
        minOf(slope, recentSlope).coerceIn(0.0, AHEAD_MAX_SLOPE) * AHEAD_HOURS

    /** BG that still needs insulin (mmol/L): the BG above target plus the expected rise. */
    fun needMmol(mmolAboveTarget: Double, slope: Double, recentSlope: Double): Double =
        (mmolAboveTarget + aheadMmol(slope, recentSlope)).coerceAtLeast(MIN_NEED_MMOL)

    /** IOB (U) per mmol/L of BG that still needs insulin. 0 without IOB. */
    fun iobPerNeed(iob: Double, mmolAboveTarget: Double, slope: Double, recentSlope: Double): Double =
        if (iob <= 0.0) 0.0 else iob / needMmol(mmolAboveTarget, slope, recentSlope)

    /** Decay (1.0 down to [FLOOR]) for a relative cover. */
    fun decay(relativeCover: Double): Double =
        1.0 - (1.0 - FLOOR) * smooth01((relativeCover - START) / SPAN)

    /**
     * Part of the new decay in the blend: [WEIGHT_AT_100] at strength 1.0, linear in the strength (0 to 1.5),
     * times the day weight (1 = day, 0 = night).
     */
    fun weight(strength: Double, dayWeight: Double): Double =
        strength.coerceIn(0.0, 1.5) * WEIGHT_AT_100 * dayWeight.coerceIn(0.0, 1.0)

    /**
     * How much a weaker decay (a bigger dose) counts, 0 to 1. The lower of the two slopes is used.
     * 0 with a flat or falling BG, 1 from [RISE_FULL_SLOPE].
     */
    fun riseWeight(slope: Double, recentSlope: Double): Double =
        smooth01(minOf(slope, recentSlope) / RISE_FULL_SLOPE)

    /**
     * How much the BG "still needs insulin", 0 to 1: far enough above target, still rising, and a low relative cover.
     * [dayWeight] is 1 by day and 0 at night. Every part is a smooth ramp.
     */
    fun needsWeight(
        mmolAboveTarget: Double,
        slope: Double,
        recentSlope: Double,
        relativeCover: Double,
        dayWeight: Double
    ): Double =
        smooth01((mmolAboveTarget - NEEDS_BG_START) / NEEDS_BG_SPAN) *
            riseWeight(slope, recentSlope) *
            (1.0 - smooth01((relativeCover - NEEDS_COVER_START) / NEEDS_COVER_SPAN)) *
            dayWeight.coerceIn(0.0, 1.0)

    /** Floor under the late-commit decay. 0 when nothing is needed. The strength counts up to 1.0 (100 percent). */
    fun floorMul(strength: Double, needsWeight: Double, relativeCover: Double): Double =
        strength.coerceIn(0.0, 1.0) * needsWeight.coerceIn(0.0, 1.0) * FLOOR_SHARE * decay(relativeCover)

    /**
     * Scale (0 to 1) for the post-peak cut of the commit decay. 1 = no change. The lower, the less the "turn" counts.
     * The strength counts up to 1.0 (100 percent).
     */
    fun omslagScale(strength: Double, needsWeight: Double): Double =
        1.0 - strength.coerceIn(0.0, 1.0) * needsWeight.coerceIn(0.0, 1.0)

    /** At most this part of the step brake cut is given back, at strength 100 percent and full need. A rest of the cut stays. */
    const val STEP_RELEASE_SHARE = 0.8

    /**
     * The step decay brake after the experiment. [brake] is 0 to 1, where 1 means no brake. The brake only gets weaker,
     * never stronger. The strength counts up to 1.0 (100 percent).
     */
    fun stepBrakeRelease(brake: Double, strength: Double, needsWeight: Double): Double =
        fadeOtherBrake(brake, strength.coerceIn(0.0, 1.0) * needsWeight.coerceIn(0.0, 1.0) * STEP_RELEASE_SHARE)

    /**
     * Another brake that uses the same IOB cover fades out as the experiment fades in, so the IOB is not counted twice.
     * [brake] is 0 to 1, where 1 means no brake.
     */
    fun fadeOtherBrake(brake: Double, weight: Double): Double =
        brake + (1.0 - brake) * weight.coerceIn(0.0, 1.0)

    /**
     * [refUPerMmol] NaN or 0 = no personal reference = no change. [commitNr] 1 = the first commit of a meal = no change.
     */
    fun calculate(
        strength: Double,
        dayWeight: Double,
        refUPerMmol: Double,
        commitNr: Int,
        iob: Double,
        mmolAboveTarget: Double,
        slope: Double,
        recentSlope: Double,
        oldMul: Double
    ): Result {
        val w = weight(strength, dayWeight)
        if (w <= 0.0 || commitNr <= 1 || refUPerMmol.isNaN() || refUPerMmol <= 0.0) {
            return Result(oldMul, Double.NaN, Double.NaN, 0.0)
        }
        val relativeCover = iobPerNeed(iob, mmolAboveTarget, slope, recentSlope) / refUPerMmol
        val decayMul = decay(relativeCover)
        // A weaker decay only counts while the BG rises. A stronger decay always counts.
        val part = if (decayMul > oldMul) w * riseWeight(slope, recentSlope) else w
        val blended = oldMul + (decayMul - oldMul) * part
        // Still needs insulin: a floor under the decay (it can only make the decay weaker).
        val needs = needsWeight(mmolAboveTarget, slope, recentSlope, relativeCover, dayWeight)
        val floor = floorMul(strength, needs, relativeCover)
        return Result(maxOf(blended, floor).coerceAtMost(1.0), decayMul, relativeCover, w, needs, floor)
    }

    /**
     * Estimate of the insulin (U) that the experiment added to a commit. Below zero = it held insulin back.
     *
     * The commit dose is a product in which the late-commit decay is one factor, so without the experiment the dose
     * would have been [commitDoseU] x [oldMul] / [newMul]. Only the part that was really given counts ([givenU]).
     * It is an estimate: a ceiling on the commit that does not scale with the decay makes the real difference smaller.
     * A dose that did not come from a commit gives 0.
     */
    fun commitDeltaU(commitDoseU: Double, oldMul: Double, newMul: Double, givenU: Double): Double {
        if (commitDoseU <= 0.0 || newMul <= 0.0 || givenU <= 0.0) return 0.0
        return minOf(givenU, commitDoseU) * (1.0 - oldMul / newMul)
    }

    /**
     * Stores an event when the experiment changed the decay, or when "still needs insulin" was on or the post-peak cut was
     * scaled down. [deltaU] is the estimate from [commitDeltaU]. [omslagScale] is 1 when the cut was not changed.
     */
    fun record(context: Context, nowMs: Long, oldMul: Double, result: Result, deltaU: Double, omslagScale: Double = 1.0) {
        if (result.weight > 0.0 &&
            (abs(result.blendedMul - oldMul) >= MIN_CHANGE || result.needsWeight > 0.0 || omslagScale < 0.999)
        ) {
            addEvent(
                context,
                Event(nowMs, oldMul, result.blendedMul, result.relativeCover, deltaU,
                      result.needsWeight, result.floorMul, omslagScale)
            )
        }
    }

    /** Stores an event for a cycle where only the post-peak cut was scaled down (the decay itself was not blended). */
    fun recordOmslag(context: Context, nowMs: Long, needsWeight: Double, omslagScale: Double) {
        if (omslagScale < 0.999) {
            addEvent(context, Event(nowMs, 0.0, 0.0, 0.0, 0.0, needsWeight, 0.0, omslagScale))
        }
    }

    // ── Events ────────────────────────────────────────────────────────────

    fun loadEvents(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            // Events from before 11/11/2026 have 4 parts: no insulin estimate, so 0.
            // Events from before 15/11/2026 have 5 parts: no "still needs insulin" values.
            if (p.size != 4 && p.size != 5 && p.size != 8) return@mapNotNull null
            try {
                Event(
                    p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(),
                    if (p.size >= 5) p[4].toDouble() else 0.0,
                    if (p.size == 8) p[5].toDouble() else 0.0,
                    if (p.size == 8) p[6].toDouble() else 0.0,
                    if (p.size == 8) p[7].toDouble() else 1.0
                )
            } catch (_: Exception) { null }
        }.sortedBy { it.timestampMs }
    }

    @Synchronized
    private fun addEvent(context: Context, event: Event) {
        val first = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val all = (loadEvents(context) + event).filter { it.timestampMs >= first }.takeLast(MAX_EVENTS)
        val text = all.joinToString("\n") {
            "${it.timestampMs}$SEP${it.oldMul}$SEP${it.newMul}$SEP${it.relativeCover}$SEP${it.deltaU}" +
                "$SEP${it.needsWeight}$SEP${it.floorMul}$SEP${it.omslagScale}"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_EVENTS, text).apply()
    }

    /** The event closest to [timestampMs] within [toleranceMs], for the CSV export. */
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
