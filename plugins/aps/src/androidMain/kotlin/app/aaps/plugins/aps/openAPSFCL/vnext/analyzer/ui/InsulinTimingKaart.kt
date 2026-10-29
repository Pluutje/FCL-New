package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSFCL.vnext.CommitGate
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.InsulinTimingHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.InsulinTimingHistory.Gate
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Number of days in the summary. */
private const val TIMING_WINDOW_DAYS = 28

/** One extra day is loaded, so the first meal of the window has insulin history. */
private const val TIMING_LOAD_DAYS = TIMING_WINDOW_DAYS + 1

/** Number of weeks in the trend. */
private const val TIMING_TREND_WEEKS = 4

/** Fewer meals than this: no advice, only the numbers. */
private const val TIMING_MIN_EPISODES = 5

/** Card on the Experimental tab with the timing of the insulin against the BG peak. */
@Composable
fun InsulinTimingKaart(context: Context) {
    ExperimentKaart(
        title = stringResource(R.string.fcl_timing_title),
        badge = stringResource(R.string.fcl_card_signals)
    ) {
        TimingContent(context)
    }
}

/** The content of the card. The data is only loaded when the card is open. */
@Composable
private fun TimingContent(context: Context) {
    var result by remember { mutableStateOf<InsulinTimingHistory.Result?>(null) }
    LaunchedEffect(Unit) {
        result = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val from = now - TIMING_LOAD_DAYS * 24L * 60 * 60 * 1000
            val rows = FCLAnalyzerDatabase.getInstance(context).cycleLogDao().getTimingRows(from, now + 60_000L)
            InsulinTimingHistory.analyze(rows)
        }
    }
    val zone = GvpCalculator.zone
    val today = remember { LocalDate.now(zone) }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant
    val r = result
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r == null) {
            Text(stringResource(R.string.fcl_timing_loading),
                 style = MaterialTheme.typography.bodySmall, color = headColor)
        } else {
            val summary = InsulinTimingHistory.summary(r, today, TIMING_WINDOW_DAYS, zone)
            if (summary.episodes < TIMING_MIN_EPISODES) {
                Text(stringResource(R.string.fcl_timing_no_data, TIMING_MIN_EPISODES, summary.episodes),
                     style = MaterialTheme.typography.bodySmall)
            } else {
                TimingAdvice(summary)
                TimingNumbers(summary)
                TimingGates(summary)
                TimingTrend(r, today)
            }
            Text(stringResource(R.string.fcl_timing_note, InsulinTimingHistory.ASSUMED_PEAK_MIN.roundToInt()),
                 style = MaterialTheme.typography.labelSmall, color = headColor.copy(alpha = 0.8f))
        }
    }
}

/** The readable advice: how the insulin peak lies against the BG peak and what held the first commit back. */
@Composable
private fun TimingAdvice(s: InsulinTimingHistory.Summary) {
    val lag = s.medianLagMin.roundToInt()
    val lagText = when {
        lag > InsulinTimingHistory.IN_STEP_LAG_MIN  -> stringResource(R.string.fcl_timing_adv_late, lag)
        lag < -InsulinTimingHistory.IN_STEP_LAG_MIN -> stringResource(R.string.fcl_timing_adv_early, -lag)
        else                                        -> stringResource(R.string.fcl_timing_adv_in_step, lag)
    }
    Text(lagText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    val top = InsulinTimingHistory.topGate(s)
    if (top != null && lag > InsulinTimingHistory.IN_STEP_LAG_MIN) {
        Text(stringResource(R.string.fcl_timing_gate_top, gateLabel(top.first), top.second),
             style = MaterialTheme.typography.bodySmall)
        Text(gateHint(top.first),
             style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The numbers behind the advice. */
@Composable
private fun TimingNumbers(s: InsulinTimingHistory.Summary) {
    Text(stringResource(R.string.fcl_timing_summary, TIMING_WINDOW_DAYS, s.episodes,
                        s.medianLagMin.roundToInt(), s.p25LagMin.roundToInt(), s.p75LagMin.roundToInt(),
                        (100.0 * s.lateShare).roundToInt(), InsulinTimingHistory.LATE_LAG_MIN.roundToInt()),
         style = MaterialTheme.typography.bodySmall)
    if (s.firstCommits > 0) {
        Text(stringResource(R.string.fcl_timing_wait, s.firstCommits, s.medianWaitMin.roundToInt()),
             style = MaterialTheme.typography.bodySmall)
    }
}

/** Which limit was closed first in the cycles before the first commit. */
@Composable
private fun TimingGates(s: InsulinTimingHistory.Summary) {
    val total = s.gateCounts.values.sum()
    if (total <= 0) return
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.fcl_timing_gates_title),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.fcl_timing_gates_intro),
             style = MaterialTheme.typography.labelSmall, color = headColor)
        Gate.entries.forEach { g ->
            val count = s.gateCounts[g] ?: 0
            if (count > 0) {
                Row(Modifier.fillMaxWidth()) {
                    Text(gateLabel(g), Modifier.weight(2.2f), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.fcl_timing_gate_cell, count, (100.0 * count / total).roundToInt()),
                         Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                         fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                }
            }
        }
    }
}

/** Median lag per week over the last weeks, oldest week first. */
@Composable
private fun TimingTrend(r: InsulinTimingHistory.Result, today: LocalDate) {
    val weeks = remember(r) { InsulinTimingHistory.weeklyLag(r, today, TIMING_TREND_WEEKS, GvpCalculator.zone) }
    val dayFormat = remember { DateTimeFormatter.ofPattern("d MMM") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.fcl_timing_trend_title),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.fcl_timing_trend_intro),
             style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        weeks.forEach { w ->
            Row(Modifier.fillMaxWidth()) {
                Text(w.lastDay.format(dayFormat), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (w.episodes > 0 && !w.medianLagMin.isNaN())
                        stringResource(R.string.fcl_timing_week_cell, w.medianLagMin.roundToInt(), w.episodes)
                    else
                        stringResource(R.string.fcl_tdd_no_value),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun gateLabel(g: Gate): String = when (g) {
    Gate.MEAL_NOT_RECOGNIZED -> stringResource(R.string.fcl_timing_gate_meal)
    Gate.ACCESS_LEVEL        -> stringResource(R.string.fcl_timing_gate_access)
    Gate.COMMIT_PAUSE        -> stringResource(R.string.fcl_timing_gate_pause)
    Gate.DELTA_TO_TARGET     -> stringResource(R.string.fcl_timing_gate_delta, CommitGate.MIN_DELTA_TO_TARGET.toFloat())
    Gate.SLOW_RISE           -> stringResource(R.string.fcl_timing_gate_slow)
    Gate.ACCELERATION        -> stringResource(R.string.fcl_timing_gate_accel)
    Gate.OTHER               -> stringResource(R.string.fcl_timing_gate_other)
}

@Composable
private fun gateHint(g: Gate): String = when (g) {
    Gate.MEAL_NOT_RECOGNIZED -> stringResource(R.string.fcl_timing_hint_meal)
    Gate.ACCESS_LEVEL        -> stringResource(R.string.fcl_timing_hint_access)
    Gate.COMMIT_PAUSE        -> stringResource(R.string.fcl_timing_hint_pause)
    Gate.DELTA_TO_TARGET     -> stringResource(R.string.fcl_timing_hint_delta, CommitGate.MIN_DELTA_TO_TARGET.toFloat())
    Gate.SLOW_RISE           -> stringResource(R.string.fcl_timing_hint_slow)
    Gate.ACCELERATION        -> stringResource(R.string.fcl_timing_hint_accel)
    Gate.OTHER               -> stringResource(R.string.fcl_timing_gate_other)
}
