package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner

/**
 * DFControlTab — what the day learner learned (15/11/2026).
 *
 * Shows the open proposal (accept or reject), the mode line, and the four learning cards (strength, timing,
 * persistence, frontload timing) with the 14 day history. The three sliders that move these values
 * (strength, timing, persistence) are in Settings, on the card "Dosis & gedrag".
 */
@Composable
fun DFControlTab(
    nfLevel: Double = 5.0
) {
    val context = LocalContext.current

    // Current D, F and vExtra for the learning cards (what the automaat learned)
    val d      = DFLearner.getD(context)
    val f      = DFLearner.getF(context)
    val vExtra = DFLearner.getVExtra(context)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // Open proposal. On/off and automatic/manual are in Settings → Analyser Automaat / AI Advisor.
        LearnerProposalCard(context)

        FclModeStatusLine(DFLearner.getMode(context, isNight = false), label = "Dag")

        // Strength, timing, persistence and frontload timing: what the automaat learned, with the 14 day history.
        // The three sliders in Settings move these values too.
        AdvisorAssenKaarten(
            d = d,
            f = f,
            nfLevel = nfLevel,
            vExtra = vExtra,
            agg = DFLearner.getAggLevels(context)
        )
    }
}
