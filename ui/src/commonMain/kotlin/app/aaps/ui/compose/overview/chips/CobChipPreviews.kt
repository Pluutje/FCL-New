package app.aaps.ui.compose.overview.chips

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(showBackground = true)
@Composable
internal fun CobChipPreview() {
    MaterialTheme {
        CobChip(state = CobUiState(text = "24g", cobValue = 24.0))
    }
}

@Preview(showBackground = true)
@Composable
internal fun CobChipZeroPreview() {
    MaterialTheme {
        CobChip(state = CobUiState(text = "0g", cobValue = 0.0))
    }
}

@Preview(showBackground = true)
@Composable
internal fun CobChipBlinkingPreview() {
    MaterialTheme {
        CobChip(state = CobUiState(text = "12g 45req", carbsReq = 45, cobValue = 12.0))
    }
}

// 11/09/2026 (de gebruiker) — Temp Override wint dit chip-slot als hij actief is, zelfs met
// COB=0 (het normale geval voor deze gebruiker) — zie kdoc bij ChipsUiState.CobUiState.
@Preview(showBackground = true)
@Composable
internal fun CobChipTempOverridePreview() {
    MaterialTheme {
        CobChip(state = CobUiState(cobValue = 0.0, tempOverrideActive = true, tempOverrideText = "TA 70% · 1u15m"))
    }
}
