package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSFCL.vnext.CoverDecay
import app.aaps.plugins.aps.openAPSFCL.vnext.DowntrendPass
import app.aaps.plugins.aps.openAPSFCL.vnext.DowntrendPassLog
import app.aaps.plugins.aps.openAPSFCL.vnext.ExperimentStrengths
import app.aaps.plugins.aps.openAPSFCL.vnext.ExperimentStrengths.Experiment
import app.aaps.plugins.aps.openAPSFCL.vnext.FastMealCap
import app.aaps.plugins.aps.openAPSFCL.vnext.InsulinBudget
import app.aaps.plugins.aps.openAPSFCL.vnext.ReboundBrake
import app.aaps.plugins.aps.openAPSFCL.vnext.SlowRiseSupport
import kotlin.math.roundToInt

/** Number of days in the totals. */
private const val STRENGTH_WINDOW_DAYS = 7

/**
 * One card with the two experiments for slow and fast meals ([SlowRiseSupport] and [FastMealCap]). Each experiment has its own strength slider (0 = off, the default). They are on one card so a
 * tester can find them together and test them one by one.
 */
@Composable
fun MaaltijdExperimentenKaart(context: Context) {
    var slow by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.SLOW_RISE)) }
    var fast by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.FAST_MEAL)) }
    val sinceMs = System.currentTimeMillis() - STRENGTH_WINDOW_DAYS * 86_400_000L
    val slowEvents = remember { SlowRiseSupport.loadEvents(context).filter { it.timestampMs >= sinceMs } }
    val fastEvents = remember { FastMealCap.loadEvents(context).filter { it.timestampMs >= sinceMs } }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant

    ExperimentKaart(
        title = stringResource(R.string.fcl_meals_title),
        badge = stringResource(if (slow > 0 || fast > 0) R.string.fcl_card_adjusts else R.string.fcl_card_signals),
        extraLine = stringResource(R.string.fcl_meals_state, slow, fast)
    ) {
        Text(stringResource(R.string.fcl_meals_intro), style = MaterialTheme.typography.labelSmall, color = headColor)
        Text(stringResource(R.string.fcl_strength_scale), style = MaterialTheme.typography.labelSmall, color = headColor)

        StrengthSectie(
            context, Experiment.SLOW_RISE, slow, { slow = it },
            R.string.fcl_slow_title, R.string.fcl_slow_intro, R.string.fcl_slow_note
        ) {
            if (slowEvents.isEmpty()) {
                Text(stringResource(R.string.fcl_strength_results_none), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.fcl_slow_results, STRENGTH_WINDOW_DAYS, slowEvents.size, slowEvents.sumOf { it.doseU }.toFloat()),
                     style = MaterialTheme.typography.bodySmall)
            }
        }

        StrengthSectie(
            context, Experiment.FAST_MEAL, fast, { fast = it },
            R.string.fcl_fast_title, R.string.fcl_fast_intro, R.string.fcl_fast_note
        ) {
            if (fastEvents.isEmpty()) {
                Text(stringResource(R.string.fcl_strength_results_none), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.fcl_fast_results, STRENGTH_WINDOW_DAYS, fastEvents.size, fastEvents.sumOf { it.cutU }.toFloat()),
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Card for the experiment "Doorlaat bij lichte daling" ([DowntrendPass]): a strength slider from 0 to 150 percent
 * (0 = off, the default) and the results of the last days.
 */
@Composable
fun DaalDoorlaatKaart(context: Context) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.DOWNTREND_PASS)) }
    val sinceMs = System.currentTimeMillis() - STRENGTH_WINDOW_DAYS * 86_400_000L
    val events = remember { DowntrendPassLog.loadEvents(context).filter { it.timestampMs >= sinceMs } }
    ExperimentKaart(
        title = stringResource(R.string.fcl_downpass_title),
        badge = stringResource(if (strength > 0) R.string.fcl_card_adjusts else R.string.fcl_card_signals),
        extraLine = stringResource(R.string.fcl_strength_state, strength)
    ) {
        Text(stringResource(R.string.fcl_strength_scale), style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        StrengthSectie(
            context, Experiment.DOWNTREND_PASS, strength, { strength = it },
            R.string.fcl_downpass_title, R.string.fcl_downpass_intro, R.string.fcl_downpass_note,
            showTitle = false,
            belowSlider = {
                val maxPass = DowntrendPass.maxPass(strength / 100.0) * 100.0
                Text(stringResource(R.string.fcl_downpass_effect, maxPass.toInt()), style = MaterialTheme.typography.bodySmall)
            }
        ) {
            if (events.isEmpty()) {
                Text(stringResource(R.string.fcl_strength_results_none), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.fcl_downpass_results, STRENGTH_WINDOW_DAYS, events.size, events.sumOf { it.doseU }.toFloat()),
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Card for the experiment "Vervolgdoses groter bij weinig actieve insuline" ([CoverDecay]): a strength slider from 0 to 150 percent
 * (0 = off, the default) and how often the decay was changed in the last days.
 */
@Composable
fun DekkingsAfbouwKaart(context: Context) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.COVER_DECAY)) }
    val sinceMs = System.currentTimeMillis() - STRENGTH_WINDOW_DAYS * 86_400_000L
    val events = remember { CoverDecay.loadEvents(context).filter { it.timestampMs >= sinceMs } }
    val hasReference = remember { !InsulinBudget.reference(context).isNaN() }
    ExperimentKaart(
        title = stringResource(R.string.fcl_coverdecay_title),
        badge = stringResource(if (strength > 0) R.string.fcl_card_adjusts else R.string.fcl_card_signals),
        extraLine = stringResource(R.string.fcl_strength_state, strength)
    ) {
        Text(stringResource(R.string.fcl_strength_scale), style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        StrengthSectie(
            context, Experiment.COVER_DECAY, strength, { strength = it },
            R.string.fcl_coverdecay_title, R.string.fcl_coverdecay_intro, R.string.fcl_coverdecay_note,
            showTitle = false,
            belowSlider = {
                if (!hasReference) {
                    Text(stringResource(R.string.fcl_coverdecay_no_reference), style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
            }
        ) {
            if (events.isEmpty()) {
                Text(stringResource(R.string.fcl_strength_results_none), style = MaterialTheme.typography.bodySmall)
            } else {
                val more = events.filter { it.deltaU >= 0.005 }
                val less = events.filter { it.deltaU <= -0.005 }
                Text(stringResource(R.string.fcl_coverdecay_results, STRENGTH_WINDOW_DAYS,
                                    more.size, more.sumOf { it.deltaU }.toFloat(),
                                    less.size, -less.sumOf { it.deltaU }.toFloat()),
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Card for the experiment "Rem op insuline na een lage BG" ([ReboundBrake]): a strength slider from 0 to 150 percent
 * (0 = off, the default) and how much insulin was held back in the last days.
 */
@Composable
fun HerstelRemKaart(context: Context) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.REBOUND_BRAKE)) }
    val sinceMs = System.currentTimeMillis() - STRENGTH_WINDOW_DAYS * 86_400_000L
    val events = remember { ReboundBrake.loadEvents(context).filter { it.timestampMs >= sinceMs } }
    ExperimentKaart(
        title = stringResource(R.string.fcl_rebound_title),
        badge = stringResource(if (strength > 0) R.string.fcl_card_adjusts else R.string.fcl_card_signals),
        extraLine = stringResource(R.string.fcl_strength_state, strength)
    ) {
        Text(stringResource(R.string.fcl_strength_scale), style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        StrengthSectie(
            context, Experiment.REBOUND_BRAKE, strength, { strength = it },
            R.string.fcl_rebound_title, R.string.fcl_rebound_intro, R.string.fcl_rebound_note,
            showTitle = false,
            belowSlider = {
                val maxCut = ReboundBrake.maxCut(strength / 100.0) * 100.0
                Text(stringResource(R.string.fcl_rebound_effect, maxCut.roundToInt()), style = MaterialTheme.typography.bodySmall)
            }
        ) {
            if (events.isEmpty()) {
                Text(stringResource(R.string.fcl_strength_results_none), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.fcl_rebound_results, STRENGTH_WINDOW_DAYS, events.size, events.sumOf { it.cutU }.toFloat()),
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * One experiment inside the card: title, explanation, a strength slider from 0 to 150 percent in steps of 10, and the results.
 * Lowering needs no PIN, raising does. [saved] is the saved strength, [onSaved] is called after a new strength is saved.
 */
@Composable
internal fun StrengthSectie(
    context: Context,
    experiment: Experiment,
    saved: Int,
    onSaved: (Int) -> Unit,
    titleRes: Int,
    introRes: Int,
    noteRes: Int,
    showTitle: Boolean = true,
    belowSlider: @Composable () -> Unit = {},
    results: (@Composable () -> Unit)? = null
) {
    var shown by remember { mutableFloatStateOf(saved.toFloat()) }
    var pending by remember { mutableStateOf<Int?>(null) }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (showTitle) {
        HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(top = 6.dp))
    }
    Text(stringResource(introRes), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(noteRes), style = MaterialTheme.typography.labelSmall, color = headColor)

    Text(stringResource(R.string.fcl_strength_choose, shown.roundToInt()),
         style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.padding(top = 6.dp))
    Slider(
        value = shown,
        onValueChange = { shown = it },
        onValueChangeFinished = {
            val step = ExperimentStrengths.STEP_PERCENT
            val target = ((shown / step).roundToInt() * step).coerceIn(0, ExperimentStrengths.MAX_PERCENT)
            shown = target.toFloat()
            if (target == saved) return@Slider
            if (ExperimentStrengths.needsPin(saved, target)) {
                pending = target
            } else {
                ExperimentStrengths.setPercent(context, experiment, target)
                onSaved(target)
            }
        },
        valueRange = 0f..ExperimentStrengths.MAX_PERCENT.toFloat(),
        steps = ExperimentStrengths.MAX_PERCENT / ExperimentStrengths.STEP_PERCENT - 1
    )

    belowSlider()

    if (results != null) {
        Text(stringResource(R.string.fcl_strength_results_title, STRENGTH_WINDOW_DAYS),
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        results()
    }

    val target = pending
    if (target != null) {
        StrengthPinDialog(
            onConfirm = {
                ExperimentStrengths.setPercent(context, experiment, target)
                shown = target.toFloat()
                onSaved(target)
                pending = null
            },
            onCancel = {
                shown = saved.toFloat()
                pending = null
            }
        )
    }
}

@Composable
private fun StrengthPinDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.fcl_strength_pin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.fcl_strength_pin_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8); wrong = false },
                    label = { Text(stringResource(R.string.fcl_strength_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = wrong,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (wrong) {
                    Text(stringResource(R.string.fcl_strength_pin_wrong), style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (ExperimentStrengths.isPinCorrect(pin)) onConfirm() else wrong = true
            }) { Text(stringResource(R.string.fcl_strength_pin_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.fcl_strength_pin_cancel)) }
        }
    )
}
