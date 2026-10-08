package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context

/**
 * Temp target and dose size (07/10/2026).
 *
 * When the switch is on, a HIGH temp target gives smaller doses and a LOW temp target gives
 * bigger doses. The user sets a high temp target on purpose (for example for sport) because he
 * wants less insulin, so the doses should follow that.
 *
 * Formula (same idea as the "exercise mode" of OpenAPS/AAPS):
 * factor = C / (C + (tempTarget - profileTarget)), with C = 3.3 mmol/L (60 mg/dL).
 * Example: profile target 6.0, temp target 7.8 -> 3.3 / (3.3 + 1.8) = 0.65.
 * The factor is limited to MIN_FACTOR..MAX_FACTOR.
 *
 * The factor is applied in one place in FCLvNext.kt (next to the TEMP OVERRIDE multiplier).
 * It is NOT applied to the extra doses of an override preset.
 */
object FclTempTargetDoseSettings {

    private const val PREFS = "fcl_temptarget_dose_settings"
    private const val KEY_ENABLED = "tt_dose_scaling_enabled"

    /** mmol/L. 60 mg/dL, same as the half-basal-exercise-target idea in AAPS. */
    const val C_MMOL = 3.3
    const val MIN_FACTOR = 0.5
    const val MAX_FACTOR = 1.3

    // Filled every cycle by OpenAPSFCLPlugin, read by FCLvNext.
    @Volatile private var profileTargetMmol: Double = 0.0
    @Volatile private var tempTargetMmol: Double? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Called by the plugin each cycle. [tempTargetMgdl] is null when there is no temp target. */
    fun update(profileTargetMgdl: Double, tempTargetMgdl: Double?) {
        profileTargetMmol = profileTargetMgdl / 18.0
        tempTargetMmol = tempTargetMgdl?.let { it / 18.0 }
    }

    /** Current temp target in mmol/L, or null when none is active. */
    fun currentTempTargetMmol(): Double? = tempTargetMmol

    fun currentProfileTargetMmol(): Double = profileTargetMmol

    /** Pure function, 1.0 when there is nothing to scale. */
    fun factor(profileTarget: Double, tempTarget: Double?): Double {
        if (tempTarget == null || profileTarget <= 0.0) return 1.0
        val denominator = C_MMOL + (tempTarget - profileTarget)
        if (denominator <= 0.0) return MAX_FACTOR
        return (C_MMOL / denominator).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }
}
