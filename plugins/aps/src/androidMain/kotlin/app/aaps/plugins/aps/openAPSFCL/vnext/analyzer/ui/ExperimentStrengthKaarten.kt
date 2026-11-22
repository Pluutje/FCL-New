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
import app.aaps.plugins.aps.openAPSFCL.vnext.ExperimentStrengths
import app.aaps.plugins.aps.openAPSFCL.vnext.ExperimentStrengths.Experiment
import kotlin.math.roundToInt

/** Number of days in the totals. */
internal const val STRENGTH_WINDOW_DAYS = 7

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
