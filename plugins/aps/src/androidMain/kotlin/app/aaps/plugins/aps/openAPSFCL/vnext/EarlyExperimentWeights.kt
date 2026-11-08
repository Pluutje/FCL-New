package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * Pure helpers for the two early-insulin experiments (strength 0 to 150 percent, 0 = off).
 *
 * - [extraDoseBonus]: extra part of the early dose when the rise keeps going ("Extra early dose at steady rise").
 * - [peakStartThreshold]: predicted peak at which a meal is watched early ("Earlier alert for a bigger meal").
 *
 * Both are relative to the normal behaviour: strength 0 gives the old default, 100 percent is the normal
 * strength, higher is more effect. No hard steps.
 */
object EarlyExperimentWeights {

    /** Extra part of the early dose at 100 percent strength. */
    const val BONUS_AT_100 = 0.15

    /** The sustain score fades in from [SUSTAIN_START] to [SUSTAIN_FULL] (was a hard step at 0.85). */
    const val SUSTAIN_START = 0.75
    const val SUSTAIN_FULL = 0.95

    /** Normal predicted peak (mmol/L) to start watching a meal. */
    const val PEAK_START_DEFAULT = 9.0

    /** The threshold goes down by this many mmol/L per 100 percent strength. */
    const val PEAK_DROP_AT_100 = 1.0

    /** Lowest threshold that was ever tested in the backtest (mmol/L). */
    const val PEAK_START_MIN = 7.5

    /**
     * @param strength strength factor, 0.0 to 1.5
     * @param sustainScore how long and steady the rise is, 0 to 1
     * @param mealConfirmed true when the meal is confirmed (not uncertain)
     * @param stage the early dose stage; the first stage never gets the bonus
     * @return extra fraction of the early dose, for example 0.15 = +15 percent
     */
    fun extraDoseBonus(strength: Double, sustainScore: Double, mealConfirmed: Boolean, stage: Int): Double {
        if (strength <= 0.0 || !mealConfirmed || stage < 2) return 0.0
        val weight = smooth01((sustainScore - SUSTAIN_START) / (SUSTAIN_FULL - SUSTAIN_START))
        return BONUS_AT_100 * strength * weight
    }

    /** Predicted peak (mmol/L) needed to start watching a meal. Strength 0 gives [PEAK_START_DEFAULT]. */
    fun peakStartThreshold(strength: Double): Double =
        (PEAK_START_DEFAULT - PEAK_DROP_AT_100 * strength.coerceAtLeast(0.0)).coerceIn(PEAK_START_MIN, PEAK_START_DEFAULT)

    /** Strength in percent that gives the same threshold as an old absolute setting. For the move of old values. */
    fun percentFromOldThreshold(threshold: Double): Int =
        Math.round((PEAK_START_DEFAULT - threshold.coerceIn(PEAK_START_MIN, PEAK_START_DEFAULT)) / PEAK_DROP_AT_100 * 10.0).toInt() * 10

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
