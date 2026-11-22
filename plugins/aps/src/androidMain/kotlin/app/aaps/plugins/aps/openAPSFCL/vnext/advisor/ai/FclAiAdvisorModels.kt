package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

/**
 * ============================================================================
 * FCL AI-Advisor — data models
 * ============================================================================
 */

/** One suggested setting change, before the user approves it. */
data class AiParamSuggestion(
    /** Key from [FclAiAdvisorRanges] (a slider, max SMB or max IOB). */
    val param: String,
    val currentValue: Double,
    val proposedValue: Double,
    val confidence: Double,        // 0.0–1.0, given by the model itself
    val reasonNl: String,          // reasoning, quotes evidenceFields
    val evidenceFields: List<String>,
    /** Set by the validator; reason of the rejection when rejected=true. */
    val rejected: Boolean = false,
    val rejectionReasonNl: String? = null
)

/** Status of a card in the UI — what the user did with it (so far). */
enum class AiSuggestionStatus { PENDING, APPROVED, REJECTED }

data class AiAdvisorRunResult(
    val generatedAtUtc: String,
    val rawModelResponse: String,
    val suggestions: List<AiParamSuggestion>,
    val parseError: String? = null,  // null = parsing/validation worked (single suggestions can still be rejected)
    /**
     * Information only, NOT a suggestion (nothing to approve). Used when the model sees a problem
     * that none of the allowed settings can solve in a safe way.
     */
    val advisoryNoteNl: String? = null
)

/** Compact report that goes to the model as input — no raw CSV. */
data class FclDailyReportPayload(
    val dateUtc: String,
    val periodHours: Int,
    val timeInRangePct: Double,
    /** Percent of the time above 10.0 mmol/L. */
    val timeAboveRangePct: Double,
    /** Percent of the time below 3.9 mmol/L. */
    val timeBelowRangePct: Double,
    val hypoCount: Int,
    val hypoMinutesTotal: Int,
    val avgTimeToPeakMin: Double?,
    /** Average drop after the BG peak inside the post window. High = much insulin around the peak. */
    val avgOvershootAfterPeakMmol: Double?,
    /** Average peak BG of the recognized meals in the period. */
    val avgPeakBgMmol: Double?,
    /** Number of recognized meals in the period. */
    val episodeCount: Int,
    /** key = setting key of [FclAiAdvisorRanges], value = the current value of that setting. */
    val activeParams: Map<String, ActiveParamSnapshot>,
    /** Short summary lines of the learner log in the period (no raw rows). */
    val learnerEventsSummary: List<String>,
    /** Short, numbered episode incidents — max ~5 per report to keep the prompt small. */
    val notableEpisodes: List<String>,
    /** One line per hypo (newest first, max 5): what came before it. Empty when there was no hypo. */
    val hypoEpisodes: List<String>,
    /** Cycles in the period with a log row. Denominator for the three counts below. */
    val totalCycles: Int,
    /** Cycles where the dose was cut by the max IOB limit as the biggest cut. */
    val cyclesCutByMaxIob: Int,
    /** Cycles where the dose was cut by the max SMB limit as the biggest cut. */
    val cyclesCutByMaxSmb: Int,
    /** Cycles where another brake (not a limit) made the biggest cut. */
    val cyclesCutByOtherBrake: Int
)

data class ActiveParamSnapshot(
    val active: Double,
    val default: Double,
    val src: String   // "setting"
)
