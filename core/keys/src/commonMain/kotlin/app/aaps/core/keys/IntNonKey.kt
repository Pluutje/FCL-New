package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class IntNonKey(
    override val key: String,
    override val defaultValue: Int,
) : IntNonPreferenceKey {

    ObjectivesManualEnacts("ObjectivesmanualEnacts", 0),
    TddCycleOffset("tdd_cycle_offset", 0),

    // Height in dp of the two graphs on the alternative home screen (BooleanKey.OverviewAlternativeScreen).
    // Separate from the graph settings of the standard home screen, so one does not change the other.
    OverviewAlternativeBgHeight("overview_alt_bg_height", 200),
    OverviewAlternativeIobHeight("overview_alt_iob_height", 100)
}