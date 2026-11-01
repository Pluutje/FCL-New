package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSFCL.vnext.InsulinBudget
import app.aaps.plugins.aps.openAPSFCL.vnext.InsulinBudget.Level
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Number of days in the totals. */
private const val BUDGET_WINDOW_DAYS = 7

/**
 * Card of the limit per rise ([InsulinBudget]): the level the user can choose, with a plain description of each level,
 * the personal measure the limit is based on and what it did in the last days.
 */
@Composable
fun InsulinBudgetKaart(context: Context) {
    var level by remember { mutableStateOf(InsulinBudget.level(context)) }
    var events by remember { mutableStateOf<List<InsulinBudget.Event>>(emptyList()) }
    LaunchedEffect(level) {
        events = withContext(Dispatchers.IO) { InsulinBudget.loadEvents(context) }
    }
    val today = remember { LocalDate.now(GvpCalculator.zone) }
    val summary = remember(events) { InsulinBudget.summary(events, today, BUDGET_WINDOW_DAYS) }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant

    ExperimentKaart(
        title = stringResource(R.string.fcl_budget_title),
        badge = stringResource(if (level.acts) R.string.fcl_card_adjusts else R.string.fcl_card_signals),
        extraLine = stringResource(R.string.fcl_budget_state, stringResource(levelName(level)))
    ) {
        Text(stringResource(R.string.fcl_budget_intro), style = MaterialTheme.typography.labelSmall, color = headColor)

        Text(stringResource(R.string.fcl_budget_choose),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(top = 6.dp))
        Level.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    level = option
                    InsulinBudget.setLevel(context, option)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = level == option, onClick = {
                    level = option
                    InsulinBudget.setLevel(context, option)
                })
                Column(Modifier.weight(1f)) {
                    Text(stringResource(levelName(option)), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(levelDescription(option)), style = MaterialTheme.typography.labelSmall, color = headColor)
                }
            }
        }

        Text(stringResource(R.string.fcl_budget_ref_title),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(top = 6.dp))
        val ref = InsulinBudget.reference(context)
        if (ref.isNaN()) {
            Text(stringResource(R.string.fcl_budget_ref_none, InsulinBudget.referenceEpisodes(context), InsulinBudget.MIN_EPISODES),
                 style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.fcl_budget_ref, ref.toFloat(), InsulinBudget.referenceEpisodes(context)),
                 style = MaterialTheme.typography.bodySmall)
        }

        Text(stringResource(R.string.fcl_budget_results_title, BUDGET_WINDOW_DAYS),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(top = 6.dp))
        if (summary.cuts == 0) {
            Text(stringResource(R.string.fcl_budget_results_none), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(if (level.acts) R.string.fcl_budget_results_applied else R.string.fcl_budget_results_measure,
                                summary.cuts, summary.cutU.toFloat(), summary.days),
                 style = MaterialTheme.typography.bodySmall)
        }
        if (summary.brakeCycles > 0) {
            Text(stringResource(R.string.fcl_budget_brake, summary.brakeCycles), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.fcl_budget_note),
             style = MaterialTheme.typography.labelSmall, color = headColor.copy(alpha = 0.8f))
    }
}

private fun levelName(level: Level): Int = when (level) {
    Level.MEASURE -> R.string.fcl_budget_level_measure
    Level.LIGHT    -> R.string.fcl_budget_level_light
    Level.MODERATE -> R.string.fcl_budget_level_moderate
    Level.STRONG   -> R.string.fcl_budget_level_strong
}

private fun levelDescription(level: Level): Int = when (level) {
    Level.MEASURE -> R.string.fcl_budget_desc_measure
    Level.LIGHT    -> R.string.fcl_budget_desc_light
    Level.MODERATE -> R.string.fcl_budget_desc_moderate
    Level.STRONG   -> R.string.fcl_budget_desc_strong
}
