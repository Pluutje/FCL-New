package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * Max IOB during a positive override (17/11/2026).
 *
 * WHY: a manual bolus (for example for a meal at a high BG) counts as IOB. The brakes work on IOB / max IOB, so the loop
 * holds back its own doses. A user who starts an override with a factor above 100 percent wants more insulin, so the
 * max IOB goes up a bit too.
 *
 * HOW: the max IOB that FCL works with is multiplied by `1 + (factor - 1) x [SHARE]`. [SHARE] is 0.5, so an override of
 * 150 percent gives max IOB x 1.25. The factor is the effective override factor of this cycle: it is flat for the first
 * 75 percent of the duration and then fades back to 1.0, so the max IOB fades back the same way. Nothing changes without
 * an override, or with an override of 100 percent or lower (the max IOB is never made lower here).
 *
 * The scaled max IOB is used for the IOB ratio (all the IOB brakes) and for the hard IOB cap. Hypo protection, the post
 * hypo brake and the ceiling for a rise that flattens are not changed.
 */
object OverrideIobScale {

    /** Part of the override percentage above 100 that is given to the max IOB. */
    const val SHARE = 0.5

    /** Factor for the max IOB, 1.0 or higher. [effectiveMul] is the effective override factor (1.0 = no override). */
    fun scale(effectiveMul: Double): Double {
        if (effectiveMul.isNaN() || effectiveMul <= 1.0) return 1.0
        return 1.0 + (effectiveMul - 1.0) * SHARE
    }
}
