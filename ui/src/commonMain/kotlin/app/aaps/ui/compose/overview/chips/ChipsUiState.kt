package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Immutable

/**
 * What the overview chips show.
 *
 * Kept in their own file rather than beside `ChipsViewModel`: these are plain values with no Android
 * in them, and every composable and preview that reads them can then be shared code.
 */
@Immutable
data class IobUiState(
    val text: String = "",
    val iobTotal: Double = 0.0
)

@Immutable
data class CobUiState(
    val text: String = "",
    val carbsReq: Int = 0,
    val cobValue: Double = 0.0,
    // 11/09/2026 (de gebruiker) — Temp Override deelt dit chip-slot met COB, zie kdoc bij
    // TempOverrideStatusProvider.kt (core:interfaces) voor de aanleiding. Als beide tegelijk gelden
    // (zeldzaam) wint de override: zie CobChip.kt.
    val tempOverrideActive: Boolean = false,
    val tempOverrideText: String = ""
)

@Immutable
data class SensitivityUiState(
    val asText: String = "",
    val isfFrom: String = "",
    val isfTo: String = "",
    val dialogText: String = "",
    val ratio: Double = 1.0,
    val isEnabled: Boolean = true,
    val hasData: Boolean = false
)
