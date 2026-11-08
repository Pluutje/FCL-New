package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSFCL.vnext.EarlyExperimentWeights
import app.aaps.plugins.aps.openAPSFCL.vnext.ExperimentStrengths
import app.aaps.plugins.aps.openAPSFCL.vnext.ExperimentStrengths.Experiment

/**
 * Two early-insulin experiments, both with a strength from 0 to 150 percent like the slow and fast meal experiments
 * (0 = off, 100 = normal strength, raising needs the PIN). They replace the old T1 switch and the absolute
 * threshold slider. The old values are moved by [ExperimentStrengths.percent] until a new strength is saved.
 */

/** Card: a bit more early insulin when the rise keeps going. 100 percent = +15 percent on the early dose. */
@Composable
fun T1VersterkingKaart(context: Context) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.T1_BOOST)) }
    ExperimentKaart(
        title = stringResource(R.string.fcl_t1_title),
        badge = stringResource(if (strength > 0) R.string.fcl_card_adjusts else R.string.fcl_card_signals),
        extraLine = stringResource(R.string.fcl_strength_state, strength)
    ) {
        Text(stringResource(R.string.fcl_strength_scale), style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        StrengthSectie(
            context, Experiment.T1_BOOST, strength, { strength = it },
            R.string.fcl_t1_title, R.string.fcl_t1_intro, R.string.fcl_t1_note,
            showTitle = false,
            belowSlider = {
                val bonus = EarlyExperimentWeights.BONUS_AT_100 * strength
                Text(stringResource(R.string.fcl_t1_effect, bonus.toFloat()), style = MaterialTheme.typography.bodySmall)
            }
        )
    }
}

/** Card: the predicted peak needed to watch a meal early. 0 percent = 9,0 mmol/L, 100 percent = 8,0 mmol/L. */
@Composable
fun VroegeSignaleringKaart(context: Context) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.EARLY_ALERT)) }
    ExperimentKaart(
        title = stringResource(R.string.fcl_peakstart_title),
        badge = stringResource(if (strength > 0) R.string.fcl_card_adjusts else R.string.fcl_card_signals),
        extraLine = stringResource(R.string.fcl_strength_state, strength)
    ) {
        Text(stringResource(R.string.fcl_strength_scale), style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        StrengthSectie(
            context, Experiment.EARLY_ALERT, strength, { strength = it },
            R.string.fcl_peakstart_title, R.string.fcl_peakstart_intro, R.string.fcl_peakstart_note,
            showTitle = false,
            belowSlider = {
                val threshold = EarlyExperimentWeights.peakStartThreshold(strength / 100.0)
                Text(stringResource(R.string.fcl_peakstart_effect, threshold.toFloat()), style = MaterialTheme.typography.bodySmall)
            }
        )
    }
}
