package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * Smooth version of the old "absorption hard block" (was: dose = 0 when all of these were true at once:
 * inside the absorption window, acceleration <= -0.10, IOB ratio >= 0.35, consistency >= 0.45).
 *
 * The weight goes from 0 (no block) to 1 (full block, the old behaviour). The dose is multiplied by
 * (1 - weight). Every condition fades in over a range, and a clear steep rise high above target
 * fades the whole block out. A short dip in acceleration at 16 mmol/L with a steep slope must not stop the dose.
 *
 * Pure function, easy to test.
 */
object AbsorptionBlockWeight {

    const val ACCEL_START = -0.05      // weight starts here ...
    const val ACCEL_FULL = -0.15       // ... and is full here (old hard limit -0.10 is in the middle)
    const val IOB_START = 0.30         // IOB ratio, old hard limit 0.35 is in the middle
    const val IOB_FULL = 0.40
    const val CONSISTENCY_START = 0.35 // old hard limit 0.45 is in the middle
    const val CONSISTENCY_FULL = 0.55
    const val SLOPE_RELEASE_START = 2.0  // mmol/L/h: a steep rise ...
    const val SLOPE_RELEASE_FULL = 4.0
    const val DELTA_RELEASE_START = 3.0  // mmol/L above target: ... far above target
    const val DELTA_RELEASE_FULL = 6.0

    fun calculate(
        inAbsorptionWindow: Boolean,
        acceleration: Double,
        iobRatio: Double,
        consistency: Double,
        slope: Double,
        deltaToTarget: Double
    ): Double {
        if (!inAbsorptionWindow) return 0.0
        val accelW = smooth01((ACCEL_START - acceleration) / (ACCEL_START - ACCEL_FULL))
        val iobW = smooth01((iobRatio - IOB_START) / (IOB_FULL - IOB_START))
        val consW = smooth01((consistency - CONSISTENCY_START) / (CONSISTENCY_FULL - CONSISTENCY_START))
        val release = smooth01((slope - SLOPE_RELEASE_START) / (SLOPE_RELEASE_FULL - SLOPE_RELEASE_START)) *
            smooth01((deltaToTarget - DELTA_RELEASE_START) / (DELTA_RELEASE_FULL - DELTA_RELEASE_START))
        return accelW * iobW * consW * (1.0 - release)
    }

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
