package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSFCL.vnext.CoverTrial
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Number of days in the totals of the trial. */
private const val TRIAL_WINDOW_DAYS = 7

/**
 * Card of the cover trial ([CoverTrial]): the on/off switch at the top, the state of the trial, the
 * results of the last days and the fixed settings. The settings are only shown for now; in a later
 * version they can become sliders on this card.
 */
@Composable
fun CoverTrialKaart(context: Context) {
    var enabled by remember { mutableStateOf(CoverTrial.isEnabled(context)) }
    var refresh by remember { mutableStateOf(0) }
    var events by remember { mutableStateOf<List<CoverTrial.Event>>(emptyList()) }
    LaunchedEffect(refresh) {
        events = withContext(Dispatchers.IO) { CoverTrial.loadEvents(context) }
    }
    val today = remember { LocalDate.now(GvpCalculator.zone) }
    val summary = remember(events) { CoverTrial.summary(events, today, TRIAL_WINDOW_DAYS) }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dayFormat = remember { DateTimeFormatter.ofPattern("d MMM") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ── Switch at the top ──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.fcl_trial_title),
                         style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(if (enabled) R.string.fcl_trial_state_on else R.string.fcl_trial_state_off),
                         style = MaterialTheme.typography.labelSmall, color = headColor)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    CoverTrial.setEnabled(context, it)
                    refresh++
                })
            }
            Text(stringResource(R.string.fcl_trial_intro), style = MaterialTheme.typography.labelSmall, color = headColor)

            // ── State ──
            val s = CoverTrial.strength(context)
            Text(stringResource(R.string.fcl_trial_strength, (s * 100).roundToInt(), (CoverTrial.MAX_STRENGTH * 100).roundToInt()),
                 style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.fcl_trial_progress, CoverTrial.cleanSinceChange(context), CoverTrial.BATCH),
                 style = MaterialTheme.typography.bodySmall)
            val holdUntil = CoverTrial.holdUntilMs(context)
            if (holdUntil > System.currentTimeMillis()) {
                Text(stringResource(R.string.fcl_trial_hold,
                                    Instant.ofEpochMilli(holdUntil).atZone(GvpCalculator.zone).toLocalDate().format(dayFormat)),
                     style = MaterialTheme.typography.bodySmall)
            }
            val change = CoverTrial.lastChangeText(context)
            if (change.isNotBlank()) {
                val p = change.split(":")
                val whenText = Instant.ofEpochMilli(CoverTrial.lastChangeMs(context))
                    .atZone(GvpCalculator.zone).toLocalDate().format(dayFormat)
                if (p[0] == "LOW" && p.size >= 3) {
                    Text(stringResource(R.string.fcl_trial_last_low, whenText, p[1].toFloatOrNull() ?: 0f,
                                        ((p[2].toFloatOrNull() ?: 0f) * 100).roundToInt()),
                         style = MaterialTheme.typography.bodySmall)
                } else if (p[0] == "UP" && p.size >= 3) {
                    Text(stringResource(R.string.fcl_trial_last_up, whenText, ((p[2].toFloatOrNull() ?: 0f) * 100).roundToInt()),
                         style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── Results ──
            Text(stringResource(R.string.fcl_trial_results_title, TRIAL_WINDOW_DAYS),
                 style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.padding(top = 6.dp))
            if (summary.events == 0) {
                Text(stringResource(R.string.fcl_trial_results_none), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.fcl_trial_results, summary.events, summary.extraU.toFloat(), summary.judged),
                     style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.fcl_trial_results_lows, summary.lows, summary.highs),
                     style = MaterialTheme.typography.bodySmall)
            }

            // ── Settings (only shown) ──
            Text(stringResource(R.string.fcl_trial_settings_title),
                 style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.padding(top = 6.dp))
            SettingRow(R.string.fcl_trial_set_start, stringResource(R.string.fcl_trial_pct, (CoverTrial.START_STRENGTH * 100).roundToInt()))
            SettingRow(R.string.fcl_trial_set_step, stringResource(R.string.fcl_trial_pct, (CoverTrial.STEP * 100).roundToInt()))
            SettingRow(R.string.fcl_trial_set_max, stringResource(R.string.fcl_trial_pct, (CoverTrial.MAX_STRENGTH * 100).roundToInt()))
            SettingRow(R.string.fcl_trial_set_batch, CoverTrial.BATCH.toString())
            SettingRow(R.string.fcl_trial_set_hold, stringResource(R.string.fcl_trial_days, CoverTrial.HOLD_DAYS))
            SettingRow(R.string.fcl_trial_set_low, stringResource(R.string.fcl_trial_mmol, CoverTrial.LOW_MMOL.toFloat()))
            SettingRow(R.string.fcl_trial_set_cap_commit, stringResource(R.string.fcl_trial_units, CoverTrial.CAP_PER_COMMIT_U.toFloat()))
            SettingRow(R.string.fcl_trial_set_cap_day, stringResource(R.string.fcl_trial_units, CoverTrial.CAP_PER_DAY_U.toFloat()))
            SettingRow(R.string.fcl_trial_set_slope, stringResource(R.string.fcl_trial_slope, CoverTrial.ONSET_SLOPE.toFloat()))
            SettingRow(R.string.fcl_trial_set_above, stringResource(R.string.fcl_trial_mmol, CoverTrial.ONSET_ABOVE_TARGET.toFloat()))
            Text(stringResource(R.string.fcl_trial_settings_note),
                 style = MaterialTheme.typography.labelSmall, color = headColor.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun SettingRow(labelRes: Int, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}
