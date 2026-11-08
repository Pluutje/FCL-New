package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * Release of the "absorption" suppression (reason ABSORPTION in the post-peak part).
 *
 * After every commit the absorption window is open for 60 minutes. Inside it the dose was cut to
 * `absorptionDoseFactor` (about 8 to 15%) as soon as slope <= 0.3 or acceleration <= -0.05. That is right
 * near a peak, but wrong when the BG stays far above target with little insulin on board (7/11/2026,
 * 20:57 to 21:12: BG 15.3, IOB ratio 0.3, doses of 0 while the algorithm wanted 1 to 4.6 U).
 *
 * The release goes from 0 (full suppression, as before) to 1 (no suppression):
 * - height: 0 at [HEIGHT_START] mmol/L above target, 1 at [HEIGHT_START] + [HEIGHT_RAMP]
 * - IOB ratio: 1 at or below [IOB_FULL_BELOW], 0 at [IOB_FULL_BELOW] + [IOB_RAMP] or more
 * - not falling: 0 when the BG falls by [FALL_FULL] per 5 min or more, 1 from [FALL_START] (flat or rising)
 * The dose factor is `lerp(absorptionDoseFactor, 1, release)`. From [OPEN_AT] the suppression is switched off
 * in the other parts too (early floor and commit path), because they only know on/off.
 *
 * Pure function, easy to test.
 */
object AbsorptionSuppressRelease {

    const val HEIGHT_START = 3.0
    const val HEIGHT_RAMP = 4.0
    const val IOB_FULL_BELOW = 0.30
    const val IOB_RAMP = 0.30
    const val FALL_FULL = -0.15
    const val FALL_START = -0.05
    const val OPEN_AT = 0.90

    fun calculate(deltaToTarget: Double, iobRatio: Double, recentDelta5m: Double): Double {
        val height = smooth01((deltaToTarget - HEIGHT_START) / HEIGHT_RAMP)
        val iob = 1.0 - smooth01((iobRatio - IOB_FULL_BELOW) / IOB_RAMP)
        val notFalling = smooth01((recentDelta5m - FALL_FULL) / (FALL_START - FALL_FULL))
        return height * iob * notFalling
    }

    /** Dose factor between the normal suppression factor and 1.0. */
    fun doseFactor(suppressionFactor: Double, release: Double): Double =
        suppressionFactor + release.coerceIn(0.0, 1.0) * (1.0 - suppressionFactor)

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
