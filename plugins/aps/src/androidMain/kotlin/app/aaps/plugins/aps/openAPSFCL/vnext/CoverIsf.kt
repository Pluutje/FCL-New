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
 * The fixed curve below is the only curve the algorithm uses. (The Analyser cards that measured the factor per BG class
 * were removed on 8/11/2026: the measured value is a net value and cannot be used as cover.)
 */
object CoverIsf {

    /** Points of the curve: (mmol above target, factor). */
    // 07/11/2026 (de gebruiker): insulinegevoeligheid daalt verder bij nog hogere BG. De curve stopte bij
    // 0,50 vanaf 7 mmol/L boven target. Twee punten erbij (nog steeds glijdend): 0,42 bij 10 en 0,36 bij 13.
    // De gemeten waarde in de Analyser is netto (koolhydraten en eigen dosis zitten erin) en is dus
    // alleen een richting, niet de vorm van de curve.
    private val CURVE_X = doubleArrayOf(0.0, 1.5, 3.0, 4.5, 7.0, 10.0, 13.0)
    private val CURVE_Y = doubleArrayOf(1.0, 0.85, 0.67, 0.58, 0.50, 0.42, 0.36)

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

    // ── Commit cover brake (used by FCLvNext) ──────────

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
}
