package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DayAgg
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DoseContextHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DoseDay
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.LongTermHistory
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ParamChange
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ParamKind
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.PeriodResult
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.TddAdvice
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.TddAdviceKind
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.TddAverage
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.TddStats
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.TddWeekBlock
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.WeekdayHintKind
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.WeekdayOverview
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.WeekdayStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Average periods shown in the table, in days. */
private val TDD_PERIODS = listOf(7, 14, 30, 90)

/** Height of the bar area of both bar charts, and of the row with the value above a bar. */
private val BAR_AREA = 56.dp
private val VALUE_ROW = 18.dp

/**
 * TDD card for the Analyser:
 *  - a table with the average TDD and basal share per period;
 *  - a small bar chart per weekday (with a hint only for a clear and repeated difference);
 *  - the last weeks as blocks of 7 days (TDD bars) with TIR, TAR, TBR and the FCL share under each block;
 *  - a short advice that only shows when it can be trusted (see [TddStats]).
 * Data comes from [LongTermHistory], the same day lines as the overview charts.
 */
@Composable
fun TddKaart(context: Context) {
    var dagen by remember { mutableStateOf<List<DayAgg>>(emptyList()) }
    var dosis by remember { mutableStateOf<List<DoseDay>>(emptyList()) }
    LaunchedEffect(Unit) {
        dagen = withContext(Dispatchers.IO) { LongTermHistory.load(context) }
        dosis = withContext(Dispatchers.IO) { DoseContextHistory.load(context) }
    }
    val today = remember { LocalDate.now(GvpCalculator.zone) }
    val advice = remember(dagen) { TddStats.advice(dagen, today) }
    val perioden = remember(dagen) {
        TDD_PERIODS.map { n -> n to TddStats.average(TddStats.window(dagen, today, 1, n)) }
    }
    val weekdagen = remember(dagen) { TddStats.weekdayOverview(dagen, today) }
    val blokken = remember(dagen, dosis) { TddStats.weekBlocks(dagen, today, dosis) }
    val wijzigingen = remember(dagen, dosis) { TddStats.changeEffects(dagen, dosis, today) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.fcl_tdd_title),
                 style = MaterialTheme.typography.bodyMedium,
                 fontWeight = FontWeight.SemiBold)

            if (perioden.all { it.second == null }) {
                Text(stringResource(R.string.fcl_tdd_no_data),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                PeriodTable(perioden)
                WeekdaySection(weekdagen)
                WeekBlocksSection(blokken)
                ChangesSection(wijzigingen)
                AdviceBox(advice)
                Text(stringResource(R.string.fcl_tdd_hint_only),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

// ── Table with the averages ───────────────────────────────────────────────

@Composable
private fun PeriodTable(perioden: List<Pair<Int, TddAverage?>>) {
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.fcl_tdd_col_period), Modifier.weight(1f),
                 style = MaterialTheme.typography.labelSmall, color = headColor)
            Text(stringResource(R.string.fcl_tdd_col_average), Modifier.weight(1.2f),
                 style = MaterialTheme.typography.labelSmall, color = headColor, textAlign = TextAlign.End)
            Text(stringResource(R.string.fcl_tdd_col_basal), Modifier.weight(1f),
                 style = MaterialTheme.typography.labelSmall, color = headColor, textAlign = TextAlign.End)
        }
        perioden.forEach { (n, avg) ->
            // Only a period that is not completely filled shows its number of days.
            val label = if (avg != null && avg.days < n)
                stringResource(R.string.fcl_tdd_period_days_partial, n, avg.days)
            else
                stringResource(R.string.fcl_tdd_period_days, n)
            val value = if (avg == null) stringResource(R.string.fcl_tdd_no_period_data)
            else stringResource(R.string.fcl_tdd_units_per_day, avg.avg)
            val basal = if (avg == null || avg.basalShare.isNaN()) stringResource(R.string.fcl_tdd_no_value)
            else stringResource(R.string.fcl_tdd_percent, avg.basalShare * 100.0)
            Row(Modifier.fillMaxWidth()) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(value, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall,
                     fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                Text(basal, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                     textAlign = TextAlign.End)
            }
        }
    }
}

// ── Weekdays ──────────────────────────────────────────────────────────────

@Composable
private fun WeekdaySection(overview: WeekdayOverview) {
    val values = overview.stats.map { it.tdd }.filter { !it.isNaN() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.fcl_tdd_weekday_title, TddStats.WEEKDAY_WINDOW_DAYS),
             style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (values.isEmpty()) {
            Text(stringResource(R.string.fcl_tdd_weekday_not_enough),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        WeekdayBars(overview.stats, overview.meanTdd)
        Text(stringResource(R.string.fcl_tdd_weekday_mean_line),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        val flagged = overview.stats.filter { it.hint != null }
        if (flagged.isEmpty()) {
            Text(stringResource(
                if (overview.enoughDays) R.string.fcl_tdd_weekday_none else R.string.fcl_tdd_weekday_not_enough),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            flagged.forEach { st ->
                Text(weekdayHintText(st), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun weekdayHintText(st: WeekdayStat): String {
    val name = st.dow.getDisplayName(TextStyle.FULL, Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    val change = abs(st.tddChangePct)
    return when (st.hint) {
        WeekdayHintKind.UP_TAR_HIGHER -> stringResource(R.string.fcl_tdd_weekday_up_tar_higher, name, change, st.diffPoints)
        WeekdayHintKind.UP_TAR_OK -> stringResource(R.string.fcl_tdd_weekday_up_tar_ok, name, change)
        WeekdayHintKind.DOWN_TBR_HIGHER -> stringResource(R.string.fcl_tdd_weekday_down_tbr_higher, name, change, st.diffPoints)
        WeekdayHintKind.DOWN_TBR_OK -> stringResource(R.string.fcl_tdd_weekday_down_tbr_ok, name, change)
        null -> ""
    }
}

/** Seven bars starting at 0 (so a small difference does not look big) and a dashed average line. */
@Composable
private fun WeekdayBars(stats: List<WeekdayStat>, mean: Double) {
    val top = max(stats.maxOf { if (it.tdd.isNaN()) 0.0 else it.tdd }, if (mean.isNaN()) 0.0 else mean)
    if (top <= 0.0) return
    val normalColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val flagColor = MaterialTheme.colorScheme.tertiary
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            stats.forEach { st ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(VALUE_ROW), contentAlignment = Alignment.Center) {
                        Text(if (st.tdd.isNaN()) stringResource(R.string.fcl_tdd_no_value) else "%.0f".format(st.tdd),
                             style = MaterialTheme.typography.labelSmall)
                    }
                    Box(Modifier.fillMaxWidth().height(BAR_AREA), contentAlignment = Alignment.BottomCenter) {
                        if (!st.tdd.isNaN()) {
                            Box(Modifier
                                    .fillMaxWidth(0.55f)
                                    .fillMaxHeight((st.tdd / top).toFloat().coerceIn(0.02f, 1f))
                                    .background(if (st.hint != null) flagColor else normalColor,
                                                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                        }
                    }
                    Text(st.dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                         style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!mean.isNaN()) {
            Canvas(Modifier.fillMaxWidth().height(BAR_AREA).offset(y = VALUE_ROW)) {
                val y = size.height * (1f - (mean / top).toFloat())
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                )
            }
        }
    }
}

// ── Last weeks ────────────────────────────────────────────────────────────

@Composable
private fun WeekBlocksSection(blocks: List<TddWeekBlock?>) {
    val present = blocks.filterNotNull()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.fcl_tdd_weeks_title, TddStats.WEEK_BLOCKS),
             style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (present.size < 2) {
            Text(stringResource(R.string.fcl_tdd_weeks_not_enough),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val top = present.maxOf { it.tdd }
        val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        val dateFormat = remember { DateTimeFormatter.ofPattern("d/M") }
        Row(Modifier.fillMaxWidth()) {
            blocks.forEachIndexed { i, b ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(VALUE_ROW), contentAlignment = Alignment.Center) {
                        Text(if (b == null) stringResource(R.string.fcl_tdd_no_value) else "%.0f".format(b.tdd),
                             style = MaterialTheme.typography.labelSmall)
                    }
                    Box(Modifier.fillMaxWidth().height(BAR_AREA), contentAlignment = Alignment.BottomCenter) {
                        if (b != null) {
                            Box(Modifier
                                    .fillMaxWidth(0.6f)
                                    .fillMaxHeight((b.tdd / top).toFloat().coerceIn(0.02f, 1f))
                                    .background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                        }
                    }
                    Text(if (b == null) "" else b.start.format(dateFormat),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val previous = blocks.getOrNull(i - 1)
                    val worse = b != null && previous != null && !b.tirPct.isNaN() && !previous.tirPct.isNaN() &&
                        previous.tirPct - b.tirPct >= TddStats.TIR_LIMIT
                    Text(if (b == null || b.tirPct.isNaN()) stringResource(R.string.fcl_tdd_no_value)
                         else stringResource(R.string.fcl_tdd_percent, b.tirPct),
                         style = MaterialTheme.typography.labelSmall,
                         fontWeight = if (worse) FontWeight.SemiBold else FontWeight.Normal,
                         color = if (worse) MaterialTheme.colorScheme.error
                         else MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(b?.tarPct, b?.tbrPct, b?.fclSharePct).forEach { v ->
                        Text(if (v == null || v.isNaN()) stringResource(R.string.fcl_tdd_no_value)
                             else stringResource(R.string.fcl_tdd_percent, v),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }
                }
            }
        }
        Text(stringResource(R.string.fcl_tdd_weeks_legend),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

// ── Effect of setting changes ─────────────────────────────────────────────

@Composable
private fun ChangesSection(changes: List<ParamChange>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.fcl_tdd_changes_title),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (changes.isEmpty()) {
            Text(stringResource(R.string.fcl_tdd_changes_none, TddStats.CHANGE_LOOKBACK_DAYS),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val dateFormat = remember { DateTimeFormatter.ofPattern("d/M") }
        changes.forEach { c ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(stringResource(R.string.fcl_tdd_change_head, c.date.format(dateFormat),
                                    paramName(c.kind), paramValueText(c, c.from), paramValueText(c, c.to)),
                     style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                if (c.before == null) {
                    Text(stringResource(R.string.fcl_tdd_change_no_before),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (c.after == null) {
                    Text(stringResource(R.string.fcl_tdd_change_too_short, TddStats.MIN_CHANGE_DAYS),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(resultLine(stringResource(R.string.fcl_tdd_change_before), c.before),
                         style = MaterialTheme.typography.labelSmall)
                    Text(resultLine(stringResource(R.string.fcl_tdd_change_after), c.after),
                         style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(stringResource(R.string.fcl_tdd_changes_warning, TddStats.CHANGE_WINDOW),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

@Composable
private fun paramName(kind: ParamKind): String = stringResource(
    when (kind) {
        ParamKind.STERKTE -> R.string.fcl_tdd_param_sterkte
        ParamKind.TIMING -> R.string.fcl_tdd_param_timing
        ParamKind.VOLHOUDENDHEID -> R.string.fcl_tdd_param_volhoudendheid
        ParamKind.NACHT_FACTOR -> R.string.fcl_tdd_param_nacht
        ParamKind.AAPS_MULTIPLIER -> R.string.fcl_tdd_param_aaps_multiplier
        ParamKind.DISTRIBUTION_STYLE -> R.string.fcl_tdd_param_distribution
        ParamKind.NIGHT_STYLE -> R.string.fcl_tdd_param_night_style
    }
)

@Composable
private fun paramValueText(c: ParamChange, value: String): String {
    val number = value.toDoubleOrNull()
    return if (c.kind.isPercent && number != null) stringResource(R.string.fcl_tdd_percent, number) else value
}

/** One line with the results of a window: TDD, TIR, TAR, TBR and the FCL share. */
@Composable
private fun resultLine(label: String, r: PeriodResult): String {
    val none = stringResource(R.string.fcl_tdd_no_value)
    val tir = if (r.tirPct.isNaN()) none else stringResource(R.string.fcl_tdd_percent, r.tirPct)
    val tar = if (r.tarPct.isNaN()) none else stringResource(R.string.fcl_tdd_percent, r.tarPct)
    val tbr = if (r.tbrPct.isNaN()) none else stringResource(R.string.fcl_tdd_percent, r.tbrPct)
    val fcl = if (r.fclSharePct.isNaN()) none else stringResource(R.string.fcl_tdd_percent, r.fclSharePct)
    return stringResource(R.string.fcl_tdd_change_result, label, r.days, "%.1f".format(r.tdd),
                          tir, tar, tbr, fcl)
}

// ── Advice ────────────────────────────────────────────────────────────────

@Composable
private fun AdviceBox(a: TddAdvice) {
    val (title, text) = adviceStrings(a)
    val worse = a.kind == TddAdviceKind.UP_TIR_WORSE || a.kind == TddAdviceKind.DOWN_TIR_WORSE
    val background = if (worse) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val content = if (worse) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = content)
        Text(text, style = MaterialTheme.typography.bodySmall, color = content)
    }
}

/** Title and text of the advice. The numbers in the text come from [TddAdvice]. */
@Composable
private fun adviceStrings(a: TddAdvice): Pair<String, String> = when (a.kind) {
    TddAdviceKind.NOT_ENOUGH_DATA ->
        stringResource(R.string.fcl_tdd_advice_not_enough_title) to
            stringResource(R.string.fcl_tdd_advice_not_enough,
                           TddStats.MIN_RECENT_TDD_DAYS, TddStats.RECENT_DAYS,
                           TddStats.MIN_BASE_TDD_DAYS, TddStats.BASE_DAYS, a.recentDays, a.baseDays)
    TddAdviceKind.TOO_NOISY ->
        stringResource(R.string.fcl_tdd_advice_noisy_title) to stringResource(R.string.fcl_tdd_advice_noisy)
    TddAdviceKind.STABLE ->
        stringResource(R.string.fcl_tdd_advice_stable_title) to
            stringResource(R.string.fcl_tdd_advice_stable, TddStats.RECENT_DAYS, a.changePct, TddStats.BASE_DAYS)
    TddAdviceKind.UP_TIR_WORSE ->
        stringResource(R.string.fcl_tdd_advice_up_worse_title) to
            stringResource(R.string.fcl_tdd_advice_up_worse, a.changePct, -a.tirChange, TddStats.BASE_DAYS)
    TddAdviceKind.UP_TIR_OK ->
        stringResource(R.string.fcl_tdd_advice_up_ok_title) to
            stringResource(R.string.fcl_tdd_advice_up_ok, a.changePct)
    TddAdviceKind.DOWN_TIR_OK ->
        stringResource(R.string.fcl_tdd_advice_down_ok_title) to
            stringResource(R.string.fcl_tdd_advice_down_ok, -a.changePct)
    TddAdviceKind.DOWN_TIR_WORSE ->
        stringResource(R.string.fcl_tdd_advice_down_worse_title) to
            stringResource(R.string.fcl_tdd_advice_down_worse, -a.changePct, -a.tirChange)
}
