package app.aaps.plugins.aps.openAPSFCL.compose

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.AggLevels
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ConfigOverrideWriter
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFLearner
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.DFMapping
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.StvState
import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings

/**
 * Sliders on the Settings card "Dosis & gedrag" (15/11/2026).
 *
 * Three levels (strength, timing, persistence) and one night level, each 1 to 9, 5 = no shift.
 * They moved here from the Analyser screen. There is no apply button: the value is written when
 * the user lets go of the slider.
 *
 * Under each slider is a read-only line with the value that is active now (from the preferences),
 * and a note when the value is at its limit.
 */
@Composable
fun FclAggLevelsSettings(context: Context, preferences: Preferences) {
    val s = FclStrings.get(context)
    val start = remember { DFLearner.getAggLevels(context) }
    var strength by remember { mutableIntStateOf(start.strength) }
    var timing by remember { mutableIntStateOf(start.timing) }
    var persistence by remember { mutableIntStateOf(start.persistence) }
    var night by remember { mutableIntStateOf(DFLearner.getNachtAggressiviteit(context)) }
    // Counts up after each write, so the lines below are read again.
    var refresh by remember { mutableStateOf(0) }

    fun writeLevels() {
        val levels = AggLevels(strength, timing, persistence)
        DFLearner.setAggLevels(context, levels)
        val d = DFLearner.getD(context)
        val f = DFLearner.getF(context)
        val vExtra = DFLearner.getVExtra(context)
        ConfigOverrideWriter.writeWithStvAndParams(
            stvMap = DFMapping.toStvMap(d, f, DFLearner.getNfLevel(context), vExtra, agg = levels),
            paramOverrides = DFMapping.toParamOverrides(
                d, f,
                refWmd = DFLearner.getRefWmd(context),
                refWff = DFLearner.getRefWff(context),
                refEb = DFLearner.getRefEb(context),
                refPeakBias = DFLearner.getRefPeakBias(context),
                refLcd = DFLearner.getRefLcd(context),
                vExtra = vExtra,
                agg = levels
            ),
            reason = "Settings: strength $strength, timing $timing, persistence $persistence",
            episodeCount = 0,
            context = context
        )
        refresh++
    }

    fun writeNight() {
        DFLearner.setNachtAggressiviteit(context, night)
        ConfigOverrideWriter.writeWithNfLevel(
            currentState = StvState(
                sterkte = preferences.get(IntKey.fcl_vnext_sterkte),
                timing = preferences.get(IntKey.fcl_vnext_timing),
                volhoudendheid = preferences.get(IntKey.fcl_vnext_volhoudendheid)
            ),
            newNfLevel = DFLearner.effectiveNfLevel(context),
            reason = "Settings: night level $night",
            episodeCount = 0
        )
        refresh++
    }

    // Values before the limit, to show the "limit reached" note.
    val raw = remember(strength, timing, persistence, refresh) {
        DFMapping.toStvRaw(
            DFLearner.getD(context), DFLearner.getF(context), DFLearner.getVExtra(context),
            agg = AggLevels(strength, timing, persistence)
        )
    }
    val limitS = if (raw.first > DFMapping.S_MAX) DFMapping.S_MAX else if (raw.first < DFMapping.S_MIN) DFMapping.S_MIN else null
    val limitT = if (raw.second > DFMapping.T_MAX) DFMapping.T_MAX else if (raw.second < DFMapping.T_MIN) DFMapping.T_MIN else null
    val limitV = if (raw.third > DFMapping.V_MAX) DFMapping.V_MAX else if (raw.third < DFMapping.V_MIN) DFMapping.V_MIN else null

    // Reads the preferences again after each write.
    val activeS = remember(refresh) { preferences.get(IntKey.fcl_vnext_sterkte) }
    val activeT = remember(refresh) { preferences.get(IntKey.fcl_vnext_timing) }
    val activeV = remember(refresh) { preferences.get(IntKey.fcl_vnext_volhoudendheid) }
    val activeNf = remember(refresh) { "%.1f".format(DFLearner.effectiveNfLevel(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LevelRow(s.settingsAggStrengthTitle, s.settingsAggStrengthHelp, s.settingsAggLevel(strength),
                 strength, { strength = it }, { writeLevels() },
                 s.settingsAggActive(activeS), limitS?.let { s.settingsAggLimit(it) })
        LevelRow(s.settingsAggTimingTitle, s.settingsAggTimingHelp, s.settingsAggLevel(timing),
                 timing, { timing = it }, { writeLevels() },
                 s.settingsAggActive(activeT), limitT?.let { s.settingsAggLimit(it) })
        LevelRow(s.settingsAggPersistTitle, s.settingsAggPersistHelp, s.settingsAggLevel(persistence),
                 persistence, { persistence = it }, { writeLevels() },
                 s.settingsAggActive(activeV), limitV?.let { s.settingsAggLimit(it) })
        LevelRow(s.settingsAggNightTitle, s.settingsAggNightHelp, s.settingsAggLevel(night),
                 night, { night = it }, { writeNight() },
                 s.settingsAggNightActive(activeNf), null)
    }
}

@Composable
private fun LevelRow(
    title: String,
    help: String,
    levelText: String,
    level: Int,
    onLevel: (Int) -> Unit,
    onRelease: () -> Unit,
    activeLine: String,
    limitLine: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(levelText, style = MaterialTheme.typography.bodyMedium)
        }
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = level.toFloat(),
            onValueChange = { onLevel(it.toInt().coerceIn(AggLevels.MIN, AggLevels.MAX)) },
            onValueChangeFinished = onRelease,
            valueRange = AggLevels.MIN.toFloat()..AggLevels.MAX.toFloat(),
            steps = AggLevels.MAX - AggLevels.MIN - 1
        )
        Text(activeLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (limitLine != null) {
            Text(limitLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
