package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * Two protections for a very steep rise (23/10/2026). They are always on and have no settings: they work
 * dynamically from what was just given and from the IOB.
 *
 *  1. Dynamic floor of `late_decay_mul`: during a steep rise the floor goes from 0.35 up to 0.60, but it goes
 *     back down when a lot of insulin was just given or the IOB ratio is already high.
 *  2. Limit on the bypass of the plateau cap: after a big first commit, the `bgStijgtNogFors` bypass may not
 *     let `final_dose` through without a cap again when about the peak commit was already given in the last
 *     15 minutes. The limit goes up when the BG is far above target, so a real big meal keeps its freedom.
 *
 * Incident 23/10 22:11, 22:16, 22:21: three commits of 3.4 to 3.5 U (10.5 U in 15 minutes).
 */
object SteepRiseGuards {

    // ── Dynamic floor ─────────────────────────────────────────────────────
    /** Share of the peak commit given in the last [FLOOR_WINDOW_MIN] minutes where the extra floor starts to go away. */
    const val FLOOR_GIVEN_START = 0.5
    /** ... and where it is gone completely. */
    const val FLOOR_GIVEN_FULL = 1.0
    const val FLOOR_WINDOW_MIN = 20
    /** IOB ratio where the extra floor starts to go away, and where it is gone. */
    const val FLOOR_IOB_START = 0.30
    const val FLOOR_IOB_FULL = 0.55

    // ── Limit on the bypass ───────────────────────────────────────────────
    const val BYPASS_WINDOW_MIN = 15
    /** The bypass is limited when the insulin given in the window is at least this times the peak commit. */
    const val BYPASS_LIMIT_MUL_NEAR_TARGET = 0.9
    /** The same, when the BG is [BYPASS_FAR_ABOVE_TARGET] or more above target. */
    const val BYPASS_LIMIT_MUL_FAR_ABOVE = 2.0
    const val BYPASS_NEAR_ABOVE_TARGET = 3.0
    const val BYPASS_FAR_ABOVE_TARGET = 7.0

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /**
     * Part (0 to 1) of the extra floor that stays. 1 = nothing was given yet and the IOB is low; 0 = the extra
     * floor is gone. [givenRecentU] is the insulin of the last [FLOOR_WINDOW_MIN] minutes and [referenceU] the
     * peak commit of the episode (or a share of maxSMB when there is none).
     */
    fun floorKeep(givenRecentU: Double, referenceU: Double, iobRatio: Double): Double {
        val given = if (referenceU > 1e-9) givenRecentU / referenceU else 0.0
        val keepGiven = 1.0 - smooth01((given - FLOOR_GIVEN_START) / (FLOOR_GIVEN_FULL - FLOOR_GIVEN_START))
        val keepIob = 1.0 - smooth01((iobRatio - FLOOR_IOB_START) / (FLOOR_IOB_FULL - FLOOR_IOB_START))
        return keepGiven * keepIob
    }

    /** The limit (in U) on the insulin given in the last [BYPASS_WINDOW_MIN] minutes, for a given peak commit and distance above target. */
    fun bypassLimitU(peakCommitU: Double, mmolAboveTarget: Double): Double {
        val far = smooth01((mmolAboveTarget - BYPASS_NEAR_ABOVE_TARGET) / (BYPASS_FAR_ABOVE_TARGET - BYPASS_NEAR_ABOVE_TARGET))
        val mul = BYPASS_LIMIT_MUL_NEAR_TARGET + far * (BYPASS_LIMIT_MUL_FAR_ABOVE - BYPASS_LIMIT_MUL_NEAR_TARGET)
        return peakCommitU * mul
    }
}
