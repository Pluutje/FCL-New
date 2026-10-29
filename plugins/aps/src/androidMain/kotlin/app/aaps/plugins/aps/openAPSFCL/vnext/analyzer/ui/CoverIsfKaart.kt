package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import app.aaps.plugins.aps.openAPSFCL.vnext.CoverIsf
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CoverClassSummary
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CoverDay
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.CoverIsfHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Number of days in the summary. */
private const val COVER_WINDOW_DAYS = 28

/** A class needs at least this many measurements before its number is shown. */
private const val COVER_MIN_SAMPLES = 10

/** Number of days in the shadow calculation. */
private const val SHADOW_WINDOW_DAYS = 7

/** Number of weeks in the trend. */
private const val TREND_WEEKS = 8

/** Lowest BG class (index in [CoverIsf.CLASS_LOWER_EDGES]) used in the trend: 3.0 mmol above target and higher. */
private const val TREND_MIN_CLASS = 2

/** A week needs at least this many measurements before its number is shown. */
private const val TREND_MIN_SAMPLES = 10

/** Own card (same look as the TDD card) with the measured IOB cover factor. Shown under the TDD card. */
@Composable
fun CoverIsfKaart(context: Context) {
    ExperimentKaart(
        title = stringResource(R.string.fcl_cover_title),
        badge = stringResource(R.string.fcl_card_signals)
    ) {
        CoverIsfSection(context)
    }
}

/**
 * Shows the measured IOB cover factor per BG class next to the fixed curve of the algorithm.
 * Only information: the curve is not changed by these numbers. Data comes from [CoverIsfHistory].
 */
@Composable
private fun CoverIsfSection(context: Context) {
    var days by remember { mutableStateOf<List<CoverDay>>(emptyList()) }
    LaunchedEffect(Unit) {
        days = withContext(Dispatchers.IO) { CoverIsfHistory.load(context) }
    }
    val today = remember { LocalDate.now(GvpCalculator.zone) }
    val flat = remember(days) { CoverIsfHistory.summary(days, today, CoverIsf.GROUP_FLAT, COVER_WINDOW_DAYS) }
    val rising = remember(days) { CoverIsfHistory.summary(days, today, CoverIsf.GROUP_RISING, COVER_WINDOW_DAYS) }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Measured against curve, weighted by the number of measurements, for BG 3 mmol or more above target.
    val used = remember(flat, rising) {
        (flat + rising).filter {
            it.cls >= TREND_MIN_CLASS && it.n >= COVER_MIN_SAMPLES && !it.median.isNaN()
        }
    }
    val usedN = used.sumOf { it.n }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (usedN > 0) {
            val measuredPct = (100.0 * used.sumOf { it.n * it.median } / usedN).roundToInt()
            val curvePct = (100.0 * used.sumOf { it.n * it.curve } / usedN).roundToInt()
            Text(stringResource(R.string.fcl_cover_summary, measuredPct, curvePct),
                 style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.fcl_cover_summary_why),
                 style = MaterialTheme.typography.labelSmall, color = headColor)
        }
        Text(stringResource(R.string.fcl_cover_intro, COVER_WINDOW_DAYS),
             style = MaterialTheme.typography.labelSmall, color = headColor)
        if ((flat + rising).all { it.n < COVER_MIN_SAMPLES }) {
            Text(stringResource(R.string.fcl_cover_no_data),
                 style = MaterialTheme.typography.bodySmall, color = headColor)
        } else {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.fcl_cover_col_class), Modifier.weight(1.3f),
                     style = MaterialTheme.typography.labelSmall, color = headColor)
                Text(stringResource(R.string.fcl_cover_col_flat), Modifier.weight(1.1f),
                     style = MaterialTheme.typography.labelSmall, color = headColor, textAlign = TextAlign.End)
                Text(stringResource(R.string.fcl_cover_col_rising), Modifier.weight(1.1f),
                     style = MaterialTheme.typography.labelSmall, color = headColor, textAlign = TextAlign.End)
                Text(stringResource(R.string.fcl_cover_col_curve), Modifier.weight(0.7f),
                     style = MaterialTheme.typography.labelSmall, color = headColor, textAlign = TextAlign.End)
            }
            flat.indices.forEach { CoverRow(flat[it], rising[it]) }
            CoverTrend(days, today)
        }
    }
}

/** Own card (same look as the other cards) with the check of the commit cover brake with the measured factor. */
@Composable
fun CoverShadowKaart(context: Context) {
    val today = remember { LocalDate.now(GvpCalculator.zone) }
    ExperimentKaart(
        title = stringResource(R.string.fcl_cover_shadow_title),
        badge = stringResource(R.string.fcl_card_signals)
    ) {
        CoverShadow(context, today)
    }
}

/** The shadow calculation: what the commit cover brake would have done with the measured factor. */
@Composable
private fun CoverShadow(context: Context, today: LocalDate) {
    var shadowDays by remember { mutableStateOf<List<CoverIsfHistory.ShadowDay>>(emptyList()) }
    LaunchedEffect(Unit) {
        shadowDays = withContext(Dispatchers.IO) { CoverIsfHistory.loadShadow(context) }
    }
    val s = remember(shadowDays) { CoverIsfHistory.shadowSummary(shadowDays, today, SHADOW_WINDOW_DAYS) }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.fcl_cover_shadow_intro, CoverIsf.SHADOW_FLOOR.toFloat()),
             style = MaterialTheme.typography.labelSmall, color = headColor)
        if (s.commits == 0) {
            Text(stringResource(R.string.fcl_cover_shadow_none, SHADOW_WINDOW_DAYS),
                 style = MaterialTheme.typography.bodySmall)
        } else if (s.rows == 0) {
            Text(stringResource(R.string.fcl_cover_shadow_zero), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.fcl_cover_shadow_total, SHADOW_WINDOW_DAYS, s.rows, s.commits,
                                s.extraU.toFloat(), (s.extraU / s.days.coerceAtLeast(1)).toFloat()),
                 style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.fcl_cover_shadow_high, s.highHelped), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.fcl_cover_shadow_low, s.lowRisk), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.fcl_cover_shadow_note),
             style = MaterialTheme.typography.labelSmall, color = headColor.copy(alpha = 0.8f))
    }
}

/** Weekly mean per group over the last weeks, oldest week first. */
@Composable
private fun CoverTrend(days: List<CoverDay>, today: LocalDate) {
    val flat = remember(days) { CoverIsfHistory.weeklyTrend(days, today, CoverIsf.GROUP_FLAT, TREND_WEEKS, TREND_MIN_CLASS) }
    val rising = remember(days) { CoverIsfHistory.weeklyTrend(days, today, CoverIsf.GROUP_RISING, TREND_WEEKS, TREND_MIN_CLASS) }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dayFormat = remember { DateTimeFormatter.ofPattern("d MMM") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.fcl_cover_trend_title),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.fcl_cover_trend_intro, CoverIsf.CLASS_LOWER_EDGES[TREND_MIN_CLASS].toFloat()),
             style = MaterialTheme.typography.labelSmall, color = headColor)
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.3f))
            Text(stringResource(R.string.fcl_cover_col_flat), Modifier.weight(1.1f),
                 style = MaterialTheme.typography.labelSmall, color = headColor, textAlign = TextAlign.End)
            Text(stringResource(R.string.fcl_cover_col_rising), Modifier.weight(1.1f),
                 style = MaterialTheme.typography.labelSmall, color = headColor, textAlign = TextAlign.End)
        }
        flat.indices.forEach { i ->
            Row(Modifier.fillMaxWidth()) {
                Text(flat[i].lastDay.format(dayFormat), Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
                Text(weekText(flat[i]), Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall,
                     fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                Text(weekText(rising[i]), Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall,
                     fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            }
        }
    }
}

/** Mean and number of measurements of one week, or a dash when there are too few. */
@Composable
private fun weekText(p: CoverIsfHistory.CoverWeekPoint): String =
    if (p.n >= TREND_MIN_SAMPLES && !p.mean.isNaN())
        stringResource(R.string.fcl_cover_cell, p.mean.toFloat(), p.n)
    else
        stringResource(R.string.fcl_tdd_no_value)

/** Measured value and number of measurements of one cell, or a dash when there are too few. */
@Composable
private fun cellText(s: CoverClassSummary): String =
    if (s.n >= COVER_MIN_SAMPLES && !s.median.isNaN())
        stringResource(R.string.fcl_cover_cell, s.median.toFloat(), s.n)
    else
        stringResource(R.string.fcl_tdd_no_value)

@Composable
private fun CoverRow(flat: CoverClassSummary, rising: CoverClassSummary) {
    val lower = CoverIsf.CLASS_LOWER_EDGES[flat.cls].toFloat()
    val label = if (flat.cls + 1 < CoverIsf.CLASS_COUNT)
        stringResource(R.string.fcl_cover_class_range, lower, CoverIsf.CLASS_LOWER_EDGES[flat.cls + 1].toFloat())
    else
        stringResource(R.string.fcl_cover_class_open, lower)
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
        Text(cellText(flat), Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall,
             fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        Text(cellText(rising), Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall,
             fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        Text(stringResource(R.string.fcl_cover_factor, flat.curve.toFloat()), Modifier.weight(0.7f),
             style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
    }
}
