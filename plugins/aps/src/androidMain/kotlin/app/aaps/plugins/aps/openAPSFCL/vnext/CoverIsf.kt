package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * ISF factor for the "how many mmol does the active insulin (IOB) still cover" calculation.
 *
 * At a high BG one unit of insulin lowers the BG less than the profile ISF says (AutoISF and DynISF
 * model this too). Measured in the logs: "BG - IOB x ISF" predicts the BG 3 hours later about 8 to 11 mmol
 * too low. The factor is 1.0 at target and goes down to about 0.5 far above target.
 *
 * Only used where the cover (IOB x ISF / (BG - target)) is calculated: the commit cover brake,
 * plateau hold and the IOB ceiling at an override. The peak IOB brake still uses the IOB ratio.
 *
 * The fixed curve below is the only curve the algorithm uses. `CoverIsfHistory` measures what the data
 * says per BG class and shows it next to this curve. It does not change the curve.
 */
object CoverIsf {

    /** Points of the curve: (mmol above target, factor). */
    private val CURVE_X = doubleArrayOf(0.0, 1.5, 3.0, 4.5, 7.0)
    private val CURVE_Y = doubleArrayOf(1.0, 0.85, 0.67, 0.58, 0.50)

    /** Lower edges of the BG classes (mmol above target) used to store and show the measured factor. */
    val CLASS_LOWER_EDGES = doubleArrayOf(0.0, 1.5, 3.0, 4.5, 7.0)

    /** Class count. */
    val CLASS_COUNT: Int get() = CLASS_LOWER_EDGES.size

    /**
     * The measured factor is kept in two groups, because the carbs that still have to be absorbed are
     * very different: 0 = flat or falling BG, 1 = rising BG (recent slope above [RISING_SLOPE]).
     */
    const val GROUP_FLAT = 0
    const val GROUP_RISING = 1
    const val GROUP_COUNT = 2

    /** Recent slope (mmol/L per hour) above which the BG counts as rising. */
    const val RISING_SLOPE = 1.0

    /** The group of a recent slope. */
    fun groupOf(recentSlope: Double): Int = if (recentSlope > RISING_SLOPE) GROUP_RISING else GROUP_FLAT

    /** Factor on the profile ISF for the cover calculation. 1.0 at or below target. */
    fun factor(bgMmol: Double, targetMmol: Double): Double = factorAbove(bgMmol - targetMmol)

    /** Same as [factor], but with the distance above target already known. */
    fun factorAbove(mmolAboveTarget: Double): Double {
        val x = mmolAboveTarget
        if (x <= CURVE_X.first()) return CURVE_Y.first()
        if (x >= CURVE_X.last()) return CURVE_Y.last()
        for (i in 1 until CURVE_X.size) {
            if (x <= CURVE_X[i]) {
                val t = (x - CURVE_X[i - 1]) / (CURVE_X[i] - CURVE_X[i - 1])
                return CURVE_Y[i - 1] + t * (CURVE_Y[i] - CURVE_Y[i - 1])
            }
        }
        return CURVE_Y.last()
    }

    /** The class of a distance above target. -1 = at or below target (not measured). */
    fun classOf(mmolAboveTarget: Double): Int {
        if (mmolAboveTarget < CLASS_LOWER_EDGES.first()) return -1
        var idx = 0
        for (i in CLASS_LOWER_EDGES.indices) if (mmolAboveTarget >= CLASS_LOWER_EDGES[i]) idx = i
        return idx
    }

    // ── Commit cover brake (used by FCLvNext and by the shadow calculation in the Analyser) ──────────

    /** From this cover (x) the brake starts. */
    private const val COMMIT_COVER_START = 3.0

    /** Length of the ramp: at START + RAMP the commit is cut the most. */
    private const val COMMIT_COVER_RAMP = 5.0

    /** Largest cut of the commit (0.5 = down to 50%). */
    private const val COMMIT_COVER_MAX_CUT = 0.50

    /** Lower limit of the BG above target in the cover calculation (mmol/L). */
    private const val COMMIT_COVER_MIN_NEED_MMOL = 0.5

    /** How many times the IOB covers the BG above target: IOB x ISF x factor / (BG above target). */
    fun commitCoverRatio(iob: Double, isf: Double, factor: Double, mmolAboveTarget: Double): Double =
        iob * isf * factor / mmolAboveTarget.coerceAtLeast(COMMIT_COVER_MIN_NEED_MMOL)

    /** Multiplier (0.5 to 1.0) on the commit for a given cover ratio. */
    fun commitBrake(coverRatio: Double): Double {
        val x = ((coverRatio - COMMIT_COVER_START) / COMMIT_COVER_RAMP).coerceIn(0.0, 1.0)
        return 1.0 - COMMIT_COVER_MAX_CUT * (x * x * (3.0 - 2.0 * x))
    }

    // ── Shadow calculation: "what if the measured factor was used" (nothing is dosed with it) ──────

    /** The shadow factor never goes below this, because the measured value is a net value (see the Analyser card). */
    const val SHADOW_FLOOR = 0.40

    /**
     * Factor of the shadow calculation: the measured median, not below [SHADOW_FLOOR] and not above the
     * curve. Without a measured value (NaN) the floor is used.
     */
    fun shadowFactor(measuredMedian: Double, curve: Double): Double {
        val m = if (measuredMedian.isNaN()) SHADOW_FLOOR else measuredMedian
        return minOf(curve, maxOf(m, SHADOW_FLOOR))
    }

    /** The factor of the curve in the middle of a class, for the comparison in the Analyser. */
    fun curveAtClassMiddle(cls: Int): Double {
        val lo = CLASS_LOWER_EDGES[cls]
        val hi = if (cls + 1 < CLASS_LOWER_EDGES.size) CLASS_LOWER_EDGES[cls + 1] else lo + 2.0
        return factorAbove((lo + hi) / 2.0)
    }
}
