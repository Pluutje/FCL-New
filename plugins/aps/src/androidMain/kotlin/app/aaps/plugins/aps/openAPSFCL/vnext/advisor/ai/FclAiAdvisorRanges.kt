package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * FCL AI-Advisor — the settings the day advisor may advise on (single source of truth).
 *
 * The advisor only looks at settings the USER can change himself in the app:
 * the three sliders (strength, timing, persistence), max SMB and max IOB.
 * The internal learner parameters (early boost, frontload, commit cooldown, ...) are not
 * part of the advice any more. The learner owns them.
 *
 * Safety rules, enforced here and not by the model:
 *  - a value outside [min, max] is rejected;
 *  - one advice moves a setting only a small step (see [limitStep]);
 *  - max SMB and max IOB are safety limits: they always need a tap of the user,
 *    also when the advisor is in automatic mode.
 */
object FclAiAdvisorRanges {

    enum class ValueType { DOUBLE, INT }

    /** Minimal confidence to show a suggestion as a card. */
    const val MIN_SUGGESTION_CONFIDENCE = 0.60

    // Keys. The slider keys are our own names. The two limit keys are the AAPS setting keys.
    const val KEY_STRENGTH = "agg_strength"
    const val KEY_TIMING = "agg_timing"
    const val KEY_PERSISTENCE = "agg_persistence"
    const val KEY_MAX_SMB = "max_bolus_day"
    const val KEY_MAX_IOB = "fcl_vnext_MaxIOB"

    data class ParamSpec(
        val key: String,
        val labelNl: String,
        val effectNl: String,
        val type: ValueType,
        val min: Double,
        val max: Double,
        /** Largest relative move per advice (0.15 = 15%). Used when [maxAbsoluteStep] is 0. */
        val maxRelativeStep: Double = 0.0,
        /** Largest absolute move per advice. 0 = use [maxRelativeStep]. */
        val maxAbsoluteStep: Double = 0.0,
        /** Values are rounded to a multiple of this (toward the current value). */
        val granularity: Double,
        /** Safety limit: never applied without a tap, also not in automatic mode. */
        val needsManualApproval: Boolean = false
    )

    val ALL: List<ParamSpec> = listOf(
        ParamSpec(
            KEY_STRENGTH,
            "Sterkte (schuif 1–9)",
            "Hoger = grotere doses in totaal; lager = kleinere doses. 5 = neutraal.",
            ValueType.INT, min = 1.0, max = 9.0, maxAbsoluteStep = 1.0, granularity = 1.0
        ),
        ParamSpec(
            KEY_TIMING,
            "Timing (schuif 1–9)",
            "Hoger = insuline eerder in de maaltijd; lager = later en meer gespreid. 5 = neutraal.",
            ValueType.INT, min = 1.0, max = 9.0, maxAbsoluteStep = 1.0, granularity = 1.0
        ),
        ParamSpec(
            KEY_PERSISTENCE,
            "Volhoudendheid (schuif 1–9)",
            "Hoger = blijft langer doseren bij een aanhoudende stijging, maar de hypo-bescherming wordt losser; " +
                "lager = stopt eerder. 5 = neutraal.",
            ValueType.INT, min = 1.0, max = 9.0, maxAbsoluteStep = 1.0, granularity = 1.0
        ),
        ParamSpec(
            KEY_MAX_SMB,
            "Max SMB overdag (U)",
            "Hoogste bolus in één actie overdag. Hoger = grotere vroege dosis mogelijk; lager = voorzichtiger.",
            ValueType.DOUBLE, min = 0.1, max = 8.0, maxRelativeStep = 0.15, granularity = 0.05,
            needsManualApproval = true
        ),
        ParamSpec(
            KEY_MAX_IOB,
            "Max IOB (U)",
            "Hoogste actieve insuline waarbij nog gedoseerd mag worden. Hoger = meer ruimte tijdens een lange stijging; " +
                "lager = eerder afgeremd.",
            ValueType.DOUBLE, min = 1.0, max = 25.0, maxRelativeStep = 0.15, granularity = 0.5,
            needsManualApproval = true
        )
    )

    val byKey: Map<String, ParamSpec> = ALL.associateBy { it.key }

    /** Is the value inside the absolute range? */
    fun isInRange(spec: ParamSpec, value: Double): Boolean = value in spec.min..spec.max

    /**
     * Brings a proposed value to a safe step: not further from [current] than the allowed
     * step, inside [min, max] and rounded toward [current]. Gives [current] back when nothing is left.
     */
    fun limitStep(spec: ParamSpec, current: Double, proposed: Double): Double {
        val maxDelta = if (spec.maxAbsoluteStep > 0.0) spec.maxAbsoluteStep else abs(current) * spec.maxRelativeStep
        val delta = (proposed - current).coerceIn(-maxDelta, maxDelta)
        val raw = (current + delta).coerceIn(spec.min, spec.max)
        val g = spec.granularity
        val rounded = if (raw >= current) floor(raw / g + 1e-9) * g else ceil(raw / g - 1e-9) * g
        return rounded.coerceIn(spec.min, spec.max)
    }

    /** Prompt block: key, label, effect, range and the largest step. */
    fun rangesAsPromptBlock(): String = ALL.joinToString("\n") { spec ->
        val range = if (spec.type == ValueType.INT) "${spec.min.toInt()}–${spec.max.toInt()}" else "${spec.min}–${spec.max}"
        val step = if (spec.maxAbsoluteStep > 0.0) "max ${spec.maxAbsoluteStep.toInt()} stap per voorstel"
        else "max ${(spec.maxRelativeStep * 100).toInt()}% per voorstel"
        "- ${spec.key}: ${spec.labelNl}. ${spec.effectNl} [bereik: $range; $step]"
    }
}
