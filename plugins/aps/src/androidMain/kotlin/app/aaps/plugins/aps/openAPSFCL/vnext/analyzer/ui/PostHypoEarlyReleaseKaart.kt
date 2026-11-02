package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSFCL.vnext.PostHypoEarlyRelease
import app.aaps.plugins.aps.openAPSFCL.vnext.PostHypoEarlyRelease.Level
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.GvpCalculator
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLAnalyzerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Number of days in the totals. */
private const val EARLY_WINDOW_DAYS = 7

/** Most meals in the list. */
private const val EARLY_MAX_MEALS = 8

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M HH:mm")

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(timestampMs: Long): String =
    Instant.ofEpochMilli(timestampMs).atZone(GvpCalculator.zone).format(timeFormat)

private fun formatClock(timestampMs: Long): String =
    Instant.ofEpochMilli(timestampMs).atZone(GvpCalculator.zone).format(clockFormat)

/**
 * Card of the experiment "brake lets go earlier" ([PostHypoEarlyRelease]): the level (off, low, medium, high), what it did in the
 * last days. Every step to a higher level asks for a PIN.
 */
@Composable
fun PostHypoEarlyReleaseKaart(context: Context) {
    var level by remember { mutableStateOf(PostHypoEarlyRelease.level(context)) }
    var pendingLevel by remember { mutableStateOf<Level?>(null) }
    var events by remember { mutableStateOf<List<PostHypoEarlyRelease.Event>>(emptyList()) }
    // BG points (time in ms, mmol/L) of the last days, for the peak and the lowest value of each meal.
    var points by remember { mutableStateOf<List<PostHypoEarlyRelease.BgPoint>>(emptyList()) }
    LaunchedEffect(level) {
        withContext(Dispatchers.IO) {
            events = PostHypoEarlyRelease.loadEvents(context)
            val now = System.currentTimeMillis()
            val from = now - (EARLY_WINDOW_DAYS + 1) * 86_400_000L
            points = FCLAnalyzerDatabase.getInstance(context).cycleLogDao().getInRange(from, now)
                .map { PostHypoEarlyRelease.BgPoint(it.timestampMs, it.glucoseIob.bg, it.delivery.deliveredTotal) }
        }
    }
    val today = remember { LocalDate.now(GvpCalculator.zone) }
    val summary = remember(events) { PostHypoEarlyRelease.summary(events, today, EARLY_WINDOW_DAYS) }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant

    fun choose(option: Level) {
        if (option == level) return
        if (PostHypoEarlyRelease.needsPin(level, option)) {
            pendingLevel = option
        } else {
            level = option
            PostHypoEarlyRelease.setLevel(context, option)
        }
    }

    ExperimentKaart(
        title = stringResource(R.string.fcl_early_title),
        badge = stringResource(if (level.acts) R.string.fcl_card_adjusts else R.string.fcl_card_signals),
        extraLine = stringResource(R.string.fcl_early_state, stringResource(levelName(level)))
    ) {
        Text(stringResource(R.string.fcl_early_intro), style = MaterialTheme.typography.labelSmall, color = headColor)

        Text(stringResource(R.string.fcl_early_choose),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(top = 6.dp))
        Level.entries.forEach { option ->
            Row(
                Modifier.fillMaxWidth().clickable { choose(option) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = level == option, onClick = { choose(option) })
                Column(Modifier.weight(1f)) {
                    Text(stringResource(levelName(option)), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(levelDescription(option)), style = MaterialTheme.typography.labelSmall, color = headColor)
                }
            }
        }

        Text(stringResource(R.string.fcl_early_results_title, EARLY_WINDOW_DAYS),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(top = 6.dp))
        if (summary.cycles == 0) {
            Text(stringResource(R.string.fcl_early_results_none), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.fcl_early_results, summary.meals, summary.cycles, summary.days),
                 style = MaterialTheme.typography.bodySmall)
            // Per meal, newest first: a header with the extra insulin of that meal only, then one line per moment.
            val mealsOldestFirst = remember(events) {
                PostHypoEarlyRelease.meals(PostHypoEarlyRelease.inWindow(events, today, EARLY_WINDOW_DAYS))
            }
            val ranges = remember(mealsOldestFirst, points) {
                PostHypoEarlyRelease.bgRanges(mealsOldestFirst, points, System.currentTimeMillis())
            }
            val meals = mealsOldestFirst.reversed()
            val rangesNewestFirst = ranges.reversed()
            val shown = meals.take(EARLY_MAX_MEALS)
            shown.forEachIndexed { index, meal ->
                Text(
                    stringResource(R.string.fcl_early_meal, formatTime(meal.startMs), meal.events.size, meal.extraU.toFloat()),
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                // The BG of the meal: highest and lowest value, with the time. "Still running" while the window is open.
                rangesNewestFirst.getOrNull(index)?.let { range ->
                    val low = range.low
                    val lowMs = range.lowMs
                    Text(
                        if (range.rising) {
                            stringResource(R.string.fcl_early_range_rising, range.peak.toFloat(), formatClock(range.peakMs))
                        } else if (low == null || lowMs == null) {
                            stringResource(
                                if (range.complete) R.string.fcl_early_range_nolow else R.string.fcl_early_range_running_nolow,
                                range.peak.toFloat(), formatClock(range.peakMs)
                            )
                        } else {
                            stringResource(
                                if (range.complete) R.string.fcl_early_range else R.string.fcl_early_range_running,
                                range.peak.toFloat(), formatClock(range.peakMs), low.toFloat(), formatClock(lowMs)
                            )
                        },
                        style = MaterialTheme.typography.labelSmall, color = headColor
                    )
                }
                // Newest first, also inside a meal: the last extra dose is the one that keeps working when the BG starts to drop.
                meal.events.asReversed().forEach { e ->
                    val percent = Math.round(e.weight * 100).toInt()
                    Text(
                        if (e.standardU.isNaN()) {
                            stringResource(R.string.fcl_early_line_old, formatTime(e.timestampMs), percent, e.extraU.toFloat())
                        } else {
                            stringResource(
                                R.string.fcl_early_line, formatTime(e.timestampMs), e.standardU.toFloat(), e.extraU.toFloat(),
                                e.totalU.toFloat(), percent
                            )
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (meals.size > shown.size) {
                Text(stringResource(R.string.fcl_early_more, meals.size - shown.size),
                     style = MaterialTheme.typography.labelSmall, color = headColor)
            }
        }
        Text(stringResource(R.string.fcl_early_note),
             style = MaterialTheme.typography.labelSmall, color = headColor.copy(alpha = 0.8f))
    }

    val target = pendingLevel
    if (target != null) {
        PinDialog(
            onConfirm = {
                level = target
                PostHypoEarlyRelease.setLevel(context, target)
                pendingLevel = null
            },
            onCancel = { pendingLevel = null }
        )
    }
}

@Composable
private fun PinDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.fcl_early_pin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.fcl_early_pin_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8); wrong = false },
                    label = { Text(stringResource(R.string.fcl_early_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = wrong,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (wrong) {
                    Text(stringResource(R.string.fcl_early_pin_wrong), style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (PostHypoEarlyRelease.isPinCorrect(pin)) onConfirm() else wrong = true
            }) { Text(stringResource(R.string.fcl_early_pin_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.fcl_early_pin_cancel)) }
        }
    )
}

private fun levelName(level: Level): Int = when (level) {
    Level.OFF    -> R.string.fcl_early_level_off
    Level.LOW    -> R.string.fcl_early_level_low
    Level.MEDIUM -> R.string.fcl_early_level_medium
    Level.HIGH   -> R.string.fcl_early_level_high
}

private fun levelDescription(level: Level): Int = when (level) {
    Level.OFF    -> R.string.fcl_early_desc_off
    Level.LOW    -> R.string.fcl_early_desc_low
    Level.MEDIUM -> R.string.fcl_early_desc_medium
    Level.HIGH   -> R.string.fcl_early_desc_high
}
