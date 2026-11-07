package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Experimenteel-tabblad: alle proeven en metingen op één plek, elke kaart met een eigen titel.
 * Elke kaart zegt zelf of hij de dosering aanpast of alleen signaleert. Krijgt een meting later een
 * instelling (aan/uit of een niveau), dan blijft hij op dezelfde kaart staan.
 */
@Composable
fun FclExperimentScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InsulinBudgetKaart(context)
        PostHypoEarlyReleaseKaart(context)
        MaaltijdExperimentenKaart(context)
        CoverTrialKaart(context)
        CoverIsfKaart(context)
        CoverShadowKaart(context)
        InsulinTimingKaart(context)
    }
}
