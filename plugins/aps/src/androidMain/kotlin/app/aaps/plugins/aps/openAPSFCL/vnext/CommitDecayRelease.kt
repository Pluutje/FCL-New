package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * Less late-commit decay at the start of a strong rise.
 *
 * The late-commit decay used to count every dose as one full "commit", also a small early dose of 0.4 U. After two
 * small doses the third dose was already cut to about 30 percent, while the BG still rose fast and the IOB was low
 * (8/11/2026 18:32: 1 U instead of about 2 U at BG 7).
 *
 * Now each dose counts by its size ([commitWeight]). The weighted count only replaces the old count when the rise is
 * clearly strong ([release]): the acceleration is high, the BG is already well above target and the IOB is still low.
 * All parts are gradual. Pure functions, easy to test.
 */
object CommitDecayRelease {

    /** A dose of this fraction of maxSMB counts as one full commit. */
    const val FULL_DOSE_FRACTION = 0.5

    /** The acceleration (the `accel` value of the log) fades in from [ACCEL_START] over [ACCEL_SPAN]. */
    const val ACCEL_START = 0.16
    const val ACCEL_SPAN = 0.14

    /** Delta to target (mmol/L) fades in from [DELTA_START] over [DELTA_SPAN]. */
    const val DELTA_START = 1.2
    const val DELTA_SPAN = 0.8

    /** The release fades out when the IOB ratio goes from [IOB_START] over [IOB_SPAN]. */
    const val IOB_START = 0.15
    const val IOB_SPAN = 0.20

    /** Weight 0 to 1 of one dose for the decay count. A small dose counts little, a full commit counts 1. */
    fun commitWeight(doseU: Double, maxSmb: Double): Double {
        if (doseU <= 0.0 || maxSmb <= 0.0) return 0.0
        return smooth01(doseU / (FULL_DOSE_FRACTION * maxSmb))
    }

    /** How much of the weighted count is used, 0 to 1. 0 = old behaviour. */
    fun release(acceleration: Double, deltaToTarget: Double, iobRatio: Double): Double {
        val accelW = smooth01((acceleration - ACCEL_START) / ACCEL_SPAN)
        val deltaW = smooth01((deltaToTarget - DELTA_START) / DELTA_SPAN)
        val iobW = 1.0 - smooth01((iobRatio - IOB_START) / IOB_SPAN)
        return accelW * deltaW * iobW
    }

    /** Blend from the old decay value to the weighted one. The decay never gets stronger than the old value. */
    fun blend(oldValue: Double, weightedValue: Double, release: Double): Double =
        oldValue + (weightedValue - oldValue).coerceAtLeast(0.0) * release.coerceIn(0.0, 1.0)

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
