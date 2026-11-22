package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride
import app.aaps.plugins.aps.openAPSFCL.vnext.FclOverrideBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.FclSettingsStoreBridge
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ConfigOverrideWriter
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Applies an approved AI suggestion to the setting the user could also change by hand:
 *  - the three sliders: same write as the settings card (levels + config);
 *  - max SMB and max IOB: the same setting keys as the FCL settings screen.
 *
 * Nothing else. The AI no longer writes to FclAiParamStore. Values that were approved
 * in the past stay in that store (the config writer still reads it), and the reset
 * screen can clear them ([resetAll]).
 */
object FclAiAdvisorApplier {

    sealed class ApplyResult {
        data class Applied(val param: String, val value: Double) : ApplyResult()
        data class Rejected(val reasonNl: String) : ApplyResult()
    }

    fun approve(suggestion: AiParamSuggestion, context: Context): ApplyResult {
        if (suggestion.rejected) {
            return ApplyResult.Rejected(
                "Suggestie was al afgewezen door validatie (${suggestion.rejectionReasonNl})"
            )
        }
        val spec = FclAiAdvisorRanges.byKey[suggestion.param]
            ?: return ApplyResult.Rejected("Onbekende instelling '${suggestion.param}'")

        if (!FclAiAdvisorRanges.isInRange(spec, suggestion.proposedValue)) {
            FclAiAdvisorHistoryRepository.record(suggestion, AiSuggestionStatus.REJECTED)
            return ApplyResult.Rejected("Waarde buiten bereik bij toepassen — niet toegepast")
        }

        when (suggestion.param) {
            FclAiAdvisorRanges.KEY_STRENGTH,
            FclAiAdvisorRanges.KEY_TIMING,
            FclAiAdvisorRanges.KEY_PERSISTENCE -> writeSlider(context, suggestion.param, suggestion.proposedValue.roundToInt())

            FclAiAdvisorRanges.KEY_MAX_SMB,
            FclAiAdvisorRanges.KEY_MAX_IOB -> {
                val store = FclSettingsStoreBridge.get()
                    ?: return ApplyResult.Rejected("Instellingen zijn nu niet bereikbaar — pas handmatig aan")
                store.putDouble(suggestion.param, suggestion.proposedValue)
            }

            else -> return ApplyResult.Rejected("Onbekende instelling '${suggestion.param}'")
        }

        FclAiAdvisorHistoryRepository.record(suggestion, AiSuggestionStatus.APPROVED)
        return ApplyResult.Applied(suggestion.param, suggestion.proposedValue)
    }

    fun reject(suggestion: AiParamSuggestion) {
        FclAiAdvisorHistoryRepository.record(suggestion, AiSuggestionStatus.REJECTED)
    }

    /** Same write as the settings card (FclAggLevelsSettings): levels first, then the config. */
    private fun writeSlider(context: Context, param: String, level: Int) {
        val old = DFLearner.getAggLevels(context)
        val levels = when (param) {
            FclAiAdvisorRanges.KEY_STRENGTH -> old.copy(strength = level)
            FclAiAdvisorRanges.KEY_TIMING -> old.copy(timing = level)
            else -> old.copy(persistence = level)
        }
        DFLearner.setAggLevels(context, levels)
        val d = DFLearner.getD(context)
        val f = DFLearner.getF(context)
        val vExtra = DFLearner.getVExtra(context)
        ConfigOverrideWriter.writeWithStvAndParams(
            stvMap = DFMapping.toStvMap(d, f, DFLearner.getNfLevel(context), vExtra, agg = levels),
            paramOverrides = DFMapping.toParamOverrides(
                d, f,
                refWmd = DFLearner.getRefWmd(context),
                refWff = DFLearner.getRefWff(context),
                refEb = DFLearner.getRefEb(context),
                refPeakBias = DFLearner.getRefPeakBias(context),
                refLcd = DFLearner.getRefLcd(context),
                vExtra = vExtra,
                agg = levels
            ),
            reason = "AI advice: strength ${levels.strength}, timing ${levels.timing}, persistence ${levels.persistence}",
            episodeCount = 0,
            context = context
        )
    }

    /**
     * Old AI overrides (approved before the advisor was changed) are still read by the config
     * writer. This clears them, so the learner is in control again.
     */
    fun resetAll(context: Context) {
        FclAiParamStore.clear(context)
        // The learner keeps its learned values. The history stays as an audit trail.
    }

    /**
     * Sets one removed internal parameter back (an old advisor mistake) and clears its override.
     * Only used by the reset screen for peakIobBrakeSuppressThreshold.
     */
    fun resetParam(param: String, value: Double, context: Context): ApplyResult {
        FclAiParamStore.remove(context, param)
        val overrides = when (param) {
            "peakIobBrakeSuppressThreshold" ->
                FCLvNextConfigOverride.ParamOverrides(peakIobBrakeSuppressThreshold = value)
            else -> return ApplyResult.Rejected("Parameter '$param' niet resetbaar via deze route — pas handmatig aan")
        }
        FclOverrideBridge.post(
            FCLvNextConfigOverride.Override(
                writtenAt = Instant.now().toString(),
                reason = "AI-advisor reset: $param → $value",
                paramOverrides = overrides
            )
        )
        FclAiAdvisorHistoryRepository.deleteParam(param)
        return ApplyResult.Applied(param, value)
    }
}
