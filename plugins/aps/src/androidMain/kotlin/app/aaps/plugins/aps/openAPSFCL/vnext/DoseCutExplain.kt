package app.aaps.plugins.aps.openAPSFCL.vnext

/**
 * One short sentence for the user: why the dose of this cycle is smaller than it could be (15/11/2026).
 *
 * The layer comes from [DoseCutTrace.topLayer]. The sentence is shown only when the layer really cut the dose.
 * This only explains. It does not change any dose.
 */
object DoseCutExplain {

    /** A cut smaller than this is not worth a sentence. */
    const val MIN_SENTENCE_U = 0.05

    private val alreadyMuchInsulin = setOf(
        "IOB_STEP_BRAKE", "RISING_IOB_CAP", "PEAK_SUPPRESSION", "COMMIT_COVER_BRAKE", "AFTERLOAD_GUARD", "IOB_HARD_CAP"
    )
    private val enoughForThisRise = setOf(
        "COMMIT_DECAY_ANCHOR_CAPS", "COMMIT_PEAK_CAP", "TAPER_CLAMP", "POST_COMMIT_CEILING", "BURST_CAP",
        "PEAK_APPROACH_TAPER", "EARLY_FLOOR", "EARLY_CONFIRM", "EARLY_FRONTLOAD", "WATCHING_FRONTLOAD",
        "TOP_GUARD", "FAST_MEAL_CAP"
    )
    private val maximumReached = setOf("MAX_SMB_CAP", "ABSOLUTE_SAFETY_CAP")
    private val notRisingAnyMore = setOf(
        "FALLBACK_TURN_VETO", "TRAJECTORY_DAMPING", "TRAJECTORY_HARD_BLOCK", "DOWNTREND_GATE",
        "DOWNTREND_SOFT_HOLD", "DOWNTREND_PASS_END", "POSTPEAK_LOCKOUT", "LATE_BOLUS_BLOCK"
    )
    private val lowProtection = setOf("HYPO_PROTECTION", "POST_HYPO_BRAKE", "REBOUND_BRAKE", "REBOUND_BUFFER")
    private val smallCorrectionWait = setOf(
        "HOLD_CORRECTIONS", "SMALL_CORRECTION_COOLDOWN", "ACCESS_LEVEL", "PERSIST", "MICRO_RAMP",
        "PRE_MEAL_FLOOR", "FLAT_SLOW_EXTRA"
    )
    private val partWaits = setOf("PRE_RESERVE_SPLIT", "RESERVE_STASH")
    private val sensorDoubt = setOf("SENSOR_BLIP", "FROZEN_BG")
    private val overrideSets = setOf("TEMP_OVERRIDE", "TEMP_TARGET_DOSE")

    /** The sentence, or null when there is nothing worth saying. */
    fun sentence(layer: String, topCutU: Double): String? {
        if (layer.isEmpty() || topCutU < MIN_SENTENCE_U) return null
        return when (layer) {
            in alreadyMuchInsulin -> "Er werkt al veel insuline."
            in enoughForThisRise -> "Voor deze stijging is al genoeg gegeven."
            in maximumReached -> "De maximale dosis per keer is bereikt."
            in notRisingAnyMore -> "Je bloedsuiker stijgt niet meer of daalt."
            in lowProtection -> "Bescherming tegen een lage bloedsuiker."
            in smallCorrectionWait -> "Kleine correctie: even wachten."
            in partWaits -> "Een deel wacht tot de stijging doorzet."
            in sensorDoubt -> "De sensorwaarde is nog niet zeker."
            in overrideSets -> "Je tijdelijke doel of override bepaalt deze dosis."
            "RESTART_BLOCK" -> "Na een herstart wacht het algoritme even."
            else -> "Een veiligheidsregel hield een deel van de dosis tegen."
        }
    }

    /** Every layer name that is explained by a fixed sentence. Used by the test. */
    fun knownLayers(): Set<String> =
        alreadyMuchInsulin + enoughForThisRise + maximumReached + notRisingAnyMore + lowProtection +
            smallCorrectionWait + partWaits + sensorDoubt + overrideSets + "RESTART_BLOCK"
}
