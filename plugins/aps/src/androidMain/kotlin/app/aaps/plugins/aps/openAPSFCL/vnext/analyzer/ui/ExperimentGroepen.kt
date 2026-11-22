package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSFCL.vnext.CoverDecay
import app.aaps.plugins.aps.openAPSFCL.vnext.DowntrendPass
import app.aaps.plugins.aps.openAPSFCL.vnext.DowntrendPassLog
import app.aaps.plugins.aps.openAPSFCL.vnext.EarlyExperimentWeights
import app.aaps.plugins.aps.openAPSFCL.vnext.ExperimentStrengths
import app.aaps.plugins.aps.openAPSFCL.vnext.ExperimentStrengths.Experiment
import app.aaps.plugins.aps.openAPSFCL.vnext.FastMealCap
import app.aaps.plugins.aps.openAPSFCL.vnext.InsulinBudget
import app.aaps.plugins.aps.openAPSFCL.vnext.IobRelativeLog
import app.aaps.plugins.aps.openAPSFCL.vnext.ReboundBrake
import app.aaps.plugins.aps.openAPSFCL.vnext.SlowRiseSupport
import kotlin.math.roundToInt

/**
 * The tab "Experimenteel" has three group cards (16/11/2026). Each group does one kind of thing, and has one to three
 * strength sliders. Change one slider at a time inside a group. The strength logic is the same as before
 * ([ExperimentStrengths]): 0 = off (the default), raising needs the PIN.
 *
 *  1. Start of the meal: more or earlier insulin in the first 30 minutes ([Experiment.SLOW_RISE], [Experiment.T1_BOOST],
 *     [Experiment.EARLY_ALERT]).
 *  2. During the rise: looser brakes ([Experiment.COVER_DECAY], [Experiment.IOB_RELAX], [Experiment.DOWNTREND_PASS]).
 *  3. Extra careful: less insulin ([Experiment.REBOUND_BRAKE], [Experiment.FAST_MEAL]).
 */

/** Group card: [content] gets a function to call after a strength was changed, so the header can count the active sliders. */
@Composable
private fun GroepKaart(
    titleRes: Int,
    introRes: Int,
    hintRes: Int,
    experiments: List<Experiment>,
    content: @Composable ColumnScope.(onChange: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val active = remember(version) { experiments.count { ExperimentStrengths.percent(context, it) > 0 } }
    val headColor = MaterialTheme.colorScheme.onSurfaceVariant
    ExperimentKaart(
        title = stringResource(titleRes),
        badge = stringResource(if (active > 0) R.string.fcl_card_adjusts else R.string.fcl_card_signals),
        extraLine = stringResource(R.string.fcl_group_state, active, experiments.size)
    ) {
        Text(stringResource(introRes), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(hintRes), style = MaterialTheme.typography.labelSmall, color = headColor)
        Text(stringResource(R.string.fcl_strength_scale), style = MaterialTheme.typography.labelSmall, color = headColor)
        content { version++ }
    }
}

/** Group 1: the start of the meal. */
@Composable
fun GroepMaaltijdStartKaart(context: Context) {
    GroepKaart(
        R.string.fcl_group_start_title, R.string.fcl_group_start_intro, R.string.fcl_group_start_hint,
        listOf(Experiment.SLOW_RISE, Experiment.T1_BOOST, Experiment.EARLY_ALERT)
    ) { onChange ->
        SlowRiseSectie(context, onChange)
        T1Sectie(context, onChange)
        EarlyAlertSectie(context, onChange)
    }
}

/** Group 2: looser brakes during the rise. */
@Composable
fun GroepRemmenLosserKaart(context: Context) {
    GroepKaart(
        R.string.fcl_group_loosen_title, R.string.fcl_group_loosen_intro, R.string.fcl_group_loosen_hint,
        listOf(Experiment.COVER_DECAY, Experiment.IOB_RELAX, Experiment.DOWNTREND_PASS)
    ) { onChange ->
        CoverDecaySectie(context, onChange)
        IobRelaxSectie(context, onChange)
        DowntrendSectie(context, onChange)
    }
}

/** Group 3: extra careful, less insulin. */
@Composable
fun GroepVoorzichtigKaart(context: Context) {
    GroepKaart(
        R.string.fcl_group_careful_title, R.string.fcl_group_careful_intro, R.string.fcl_group_careful_hint,
        listOf(Experiment.REBOUND_BRAKE, Experiment.FAST_MEAL)
    ) { onChange ->
        ReboundSectie(context, onChange)
        FastMealSectie(context, onChange)
    }
}

private fun sinceMs(): Long = System.currentTimeMillis() - STRENGTH_WINDOW_DAYS * 86_400_000L

@Composable
private fun SlowRiseSectie(context: Context, onChange: () -> Unit) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.SLOW_RISE)) }
    val events = remember { SlowRiseSupport.loadEvents(context).filter { it.timestampMs >= sinceMs() } }
    StrengthSectie(
        context, Experiment.SLOW_RISE, strength, { strength = it; onChange() },
        R.string.fcl_slow_title, R.string.fcl_slow_intro, R.string.fcl_slow_note
    ) {
        if (events.isEmpty()) {
            Text(stringResource(R.string.fcl_strength_results_none), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.fcl_slow_results, STRENGTH_WINDOW_DAYS, events.size, events.sumOf { it.doseU }.toFloat()),
                 style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun T1Sectie(context: Context, onChange: () -> Unit) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.T1_BOOST)) }
    StrengthSectie(
        context, Experiment.T1_BOOST, strength, { strength = it; onChange() },
        R.string.fcl_t1_title, R.string.fcl_t1_intro, R.string.fcl_t1_note,
        belowSlider = {
            val bonus = EarlyExperimentWeights.BONUS_AT_100 * strength
            Text(stringResource(R.string.fcl_t1_effect, bonus.toFloat()), style = MaterialTheme.typography.bodySmall)
        }
    )
}

@Composable
private fun EarlyAlertSectie(context: Context, onChange: () -> Unit) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.EARLY_ALERT)) }
    StrengthSectie(
        context, Experiment.EARLY_ALERT, strength, { strength = it; onChange() },
        R.string.fcl_peakstart_title, R.string.fcl_peakstart_intro, R.string.fcl_peakstart_note,
        belowSlider = {
            val threshold = EarlyExperimentWeights.peakStartThreshold(strength / 100.0)
            Text(stringResource(R.string.fcl_peakstart_effect, threshold.toFloat()), style = MaterialTheme.typography.bodySmall)
        }
    )
}

@Composable
private fun CoverDecaySectie(context: Context, onChange: () -> Unit) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.COVER_DECAY)) }
    val events = remember { CoverDecay.loadEvents(context).filter { it.timestampMs >= sinceMs() } }
    val hasReference = remember { !InsulinBudget.reference(context).isNaN() }
    StrengthSectie(
        context, Experiment.COVER_DECAY, strength, { strength = it; onChange() },
        R.string.fcl_coverdecay_title, R.string.fcl_coverdecay_intro, R.string.fcl_coverdecay_note,
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

@Composable
private fun IobRelaxSectie(context: Context, onChange: () -> Unit) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.IOB_RELAX)) }
    val loosened = remember {
        IobRelativeLog.loadEvents(context).count {
            it.timestampMs >= sinceMs() && it.relaxWeight > 0.01 && !it.factorOriginal.isNaN() && it.factorRel > it.factorOriginal
        }
    }
    val hasReference = remember { !InsulinBudget.reference(context).isNaN() }
    StrengthSectie(
        context, Experiment.IOB_RELAX, strength, { strength = it; onChange() },
        R.string.fcl_iobrelax_title, R.string.fcl_iobrelax_intro, R.string.fcl_iobrelax_note,
        belowSlider = {
            if (!hasReference) {
                Text(stringResource(R.string.fcl_iobrelax_no_reference), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    ) {
        if (loosened == 0) {
            Text(stringResource(R.string.fcl_strength_results_none), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.fcl_iobrelax_results, STRENGTH_WINDOW_DAYS, loosened), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DowntrendSectie(context: Context, onChange: () -> Unit) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.DOWNTREND_PASS)) }
    val events = remember { DowntrendPassLog.loadEvents(context).filter { it.timestampMs >= sinceMs() } }
    StrengthSectie(
        context, Experiment.DOWNTREND_PASS, strength, { strength = it; onChange() },
        R.string.fcl_downpass_title, R.string.fcl_downpass_intro, R.string.fcl_downpass_note,
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

@Composable
private fun ReboundSectie(context: Context, onChange: () -> Unit) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.REBOUND_BRAKE)) }
    val events = remember { ReboundBrake.loadEvents(context).filter { it.timestampMs >= sinceMs() } }
    StrengthSectie(
        context, Experiment.REBOUND_BRAKE, strength, { strength = it; onChange() },
        R.string.fcl_rebound_title, R.string.fcl_rebound_intro, R.string.fcl_rebound_note,
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

@Composable
private fun FastMealSectie(context: Context, onChange: () -> Unit) {
    var strength by remember { mutableIntStateOf(ExperimentStrengths.percent(context, Experiment.FAST_MEAL)) }
    val events = remember { FastMealCap.loadEvents(context).filter { it.timestampMs >= sinceMs() } }
    StrengthSectie(
        context, Experiment.FAST_MEAL, strength, { strength = it; onChange() },
        R.string.fcl_fast_title, R.string.fcl_fast_intro, R.string.fcl_fast_note
    ) {
        if (events.isEmpty()) {
            Text(stringResource(R.string.fcl_strength_results_none), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.fcl_fast_results, STRENGTH_WINDOW_DAYS, events.size, events.sumOf { it.cutU }.toFloat()),
                 style = MaterialTheme.typography.bodySmall)
        }
    }
}
