package app.aaps.plugins.aps.openAPSFCL.vnext

import android.content.Context

/**
 * Strength of the experiments for slow and fast meals (6/11/2026).
 *
 * Each experiment has one strength from 0 to [MAX_PERCENT] percent. 0 is off and is the default, so nothing changes
 * until the user moves a slider. The strength is not a switch: it scales the size of the effect of that experiment.
 * Inside each experiment all weights are gradual (no hard steps).
 */
object ExperimentStrengths {

    enum class Experiment(val key: String) {
        /** Gentle steady extra dose when the BG rises slowly but keeps rising. See [SlowRiseSupport]. */
        SLOW_RISE("slow_rise"),

        /** Lower limit per rise when the BG rises fast. See [FastMealCap]. */
        FAST_MEAL("fast_meal"),

        /** Extra part of the early dose when the rise keeps going. See [EarlyExperimentWeights.extraDoseBonus]. */
        T1_BOOST("t1_boost"),

        /** Lower predicted peak needed to watch a meal early. See [EarlyExperimentWeights.peakStartThreshold]. */
        EARLY_ALERT("early_alert"),

        /** A part of the dose passes when the BG falls mildly but is still well above target. See [DowntrendPass]. */
        DOWNTREND_PASS("downtrend_pass"),

        /** The decay after the first commit follows the IOB cover and not the number of commits. See [CoverDecay]. */
        COVER_DECAY("cover_decay"),

        /** A part of the dose is held back in the first hour after a low BG, while the BG rises again. See [ReboundBrake]. */
        REBOUND_BRAKE("rebound_brake")
    }

    /** Old settings from before 8/11/2026. They are read once, until the user saves a new strength. */
    private const val OLD_T1_PREFS = "fcl_expert_prefs"
    private const val OLD_T1_KEY = "sustain_t1_boost_active"
    private const val OLD_PEAK_PREFS = "fcl_watching_peak_start_settings"
    private const val OLD_PEAK_KEY = "watching_peak_start_threshold"

    const val PREFS_NAME = "fcl_experiment_strengths"
    const val MAX_PERCENT = 150
    const val STEP_PERCENT = 10

    /** PIN to raise a strength. Lowering or switching off needs no PIN. */
    private const val PIN = "0000"

    fun isPinCorrect(pin: String): Boolean = pin == PIN

    /** True when the new strength is higher than the saved one. */
    fun needsPin(oldPercent: Int, newPercent: Int): Boolean = newPercent > oldPercent

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Strength in percent, 0 to [MAX_PERCENT]. 0 = off. */
    fun percent(context: Context, experiment: Experiment): Int {
        val saved = prefs(context)
        if (!saved.contains(experiment.key)) return oldValuePercent(context, experiment)
        return saved.getInt(experiment.key, 0).coerceIn(0, MAX_PERCENT)
    }

    /** Strength that matches the old setting of the same experiment. 0 when there is none. */
    private fun oldValuePercent(context: Context, experiment: Experiment): Int = when (experiment) {
        Experiment.T1_BOOST ->
            if (context.getSharedPreferences(OLD_T1_PREFS, Context.MODE_PRIVATE).getBoolean(OLD_T1_KEY, false)) 100 else 0

        Experiment.EARLY_ALERT -> {
            val old = context.getSharedPreferences(OLD_PEAK_PREFS, Context.MODE_PRIVATE)
                .getFloat(OLD_PEAK_KEY, EarlyExperimentWeights.PEAK_START_DEFAULT.toFloat()).toDouble()
            EarlyExperimentWeights.percentFromOldThreshold(old).coerceIn(0, MAX_PERCENT)
        }

        else -> 0
    }

    /** Strength as a factor: 0.0 (off) to 1.5. 1.0 is the normal strength. */
    fun factor(context: Context, experiment: Experiment): Double = percent(context, experiment) / 100.0

    fun setPercent(context: Context, experiment: Experiment, percent: Int) {
        prefs(context).edit().putInt(experiment.key, percent.coerceIn(0, MAX_PERCENT)).apply()
    }
}
