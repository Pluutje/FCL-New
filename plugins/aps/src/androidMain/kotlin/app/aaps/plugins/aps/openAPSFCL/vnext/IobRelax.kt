package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * Experiment "IOB brake follows the relative cover" (15/11/2026).
 *
 * The IOB brakes use `iobRatio` = IOB / max IOB. A user with a low max IOB is braked early, also when the IOB is small
 * for the BG he has. The relative cover (see [IobRelativeLog] and [CoverDecay]) says how much IOB there is per mmol/L
 * that still needs insulin, compared with the personal reference. This experiment lets the brakes use the relative
 * cover, but only to make them LOOSER, never stricter:
 *  - IOB step brake: the IOB factor becomes `max(factor, factorRel)`, blended by [weight].
 *  - Peak brake: the ratio it sees becomes `min(iobRatio, ratioRel)`, blended by [weight].
 *
 * The max IOB stays a hard cap elsewhere. Nothing here is a hard step: the weight fades in with the number of meals
 * in the personal reference ([fade]) and fades out at night ([dayWeight]). Strength 0 (the default) gives weight 0 and
 * so exactly the old behaviour. Without a personal reference the weight is 0 too.
 */
object IobRelax {

    /** Number of reference meals where the fade-in starts and how many more meals it takes to reach full weight. */
    const val FADE_START_EPISODES = 15.0
    const val FADE_SPAN_EPISODES = 15.0

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** 0 with few reference meals, 1 after about 30. Gradual. */
    fun fade(referenceEpisodes: Int): Double =
        smooth01((referenceEpisodes - FADE_START_EPISODES) / FADE_SPAN_EPISODES)

    /**
     * Weight 0..1 of the relative cover in the brakes. [strength] is the slider as a factor (0..1.5, above 1 counts as 1).
     * [dayWeight] is 1 by day and goes to 0 at night. NaN or missing reference gives 0.
     */
    fun weight(strength: Double, referenceEpisodes: Int, dayWeight: Double, hasReference: Boolean): Double {
        if (!hasReference || strength <= 0.0) return 0.0
        return strength.coerceAtMost(1.0) * fade(referenceEpisodes) * dayWeight.coerceIn(0.0, 1.0)
    }

    /** IOB factor used for the dose. Only equal or higher than [original]. */
    fun blendFactor(original: Double, factorRel: Double, weight: Double): Double {
        if (factorRel.isNaN() || weight <= 0.0) return original
        return original + (maxOf(original, factorRel) - original) * weight.coerceIn(0.0, 1.0)
    }

    /** IOB ratio seen by the peak brake. Only equal or lower than [iobRatio]. */
    fun blendRatio(iobRatio: Double, ratioRel: Double, weight: Double): Double {
        if (ratioRel.isNaN() || weight <= 0.0) return iobRatio
        return iobRatio + (minOf(iobRatio, ratioRel) - iobRatio) * weight.coerceIn(0.0, 1.0)
    }
}
