package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The three levels (strength, timing, persistence) must give EXACTLY what the old single aggressiveness gave when
 * all three are the same. The reference below is a copy of the old formulas with one level.
 */
class AggLevelsTest {

    private val dRaster = listOf(0.85, 0.95, 1.0, 1.05, 1.2, 1.35)
    private val fRaster = listOf(0.2, 0.5, 0.6, 0.775, 0.9, 0.95)
    private val vRaster = listOf(-0.5, 0.0, 0.33, 0.5)

    private fun scale(level: Int) = (level - 5) / 4.0

    private fun oldStv(d: Double, f: Double, vExtra: Double, level: Int): Triple<Int, Int, Int> {
        val dC = d.coerceIn(DFMapping.D_MIN, DFMapping.D_MAX)
        val fC = f.coerceIn(DFMapping.F_MIN, DFMapping.F_MAX)
        val vC = vExtra.coerceIn(-0.5, 0.5)
        val sc = scale(level)
        return Triple(
            ((95 * dC) * (1.0 + sc * 0.12)).roundToInt().coerceIn(75, 130),
            ((106 + (fC - 0.5) * 40) * (1.0 + sc * 0.06)).roundToInt().coerceIn(80, 130),
            ((95 + (dC - 1.0) * 50 + vC * 30) * (1.0 + sc * 0.10)).roundToInt().coerceIn(70, 130)
        )
    }

    private fun oldParams(d: Double, f: Double, vExtra: Double, level: Int): ConfigOverrideWriter.ParamOverrides {
        val dC = d.coerceIn(DFMapping.D_MIN, DFMapping.D_MAX)
        val fC = f.coerceIn(DFMapping.F_MIN, DFMapping.F_MAX)
        val vC = vExtra.coerceIn(-0.5, 0.5)
        val sc = scale(level)
        val dEff = (dC * (1.0 + sc * 0.12)).coerceIn(DFMapping.D_MIN * 0.85, DFMapping.D_MAX * 1.15)
        val fEff = (fC + sc * (DFMapping.F_MAX - 0.5) * 0.06).coerceIn(DFMapping.F_MIN * 0.90, DFMapping.F_MAX * 1.05)
        val wmdEff = (DFMapping.REF_WMD_DEFAULT - sc * 0.25).coerceIn(0.70, 1.60)
        return ConfigOverrideWriter.ParamOverrides(
            peakPredictionThreshold = max(10.5, 12.5 - (dEff - 1.0) * 2.0),
            watchingMinDeltaToTarget = max(0.5, wmdEff - (dEff - 1.0) * 0.5 - (fEff - 0.5) * 0.8),
            watchingFrontloadFrac = min(0.90, DFMapping.REF_WFF_DEFAULT + (fEff - 0.5) * 0.40),
            earlyPeakBiasMmol = DFMapping.REF_PEAK_BIAS_DEFAULT.coerceIn(DFMapping.REF_PEAK_BIAS_MIN, DFMapping.REF_PEAK_BIAS_MAX),
            commitCooldownMinutes = max(5, (13 - (fEff - 0.5) * 10).roundToInt()),
            peakIobBrakeSuppressThreshold = max(0.30, 0.22 - (fEff - 0.5) * 0.10),
            peakPredictionHorizonH = max(0.8, 1.2 - (fEff - 0.5) * 0.6),
            iobStart = max(0.10, 0.25 - (fEff - 0.5) * 0.20),
            earlyBoostFactor = max(1.0, DFMapping.REF_EB_DEFAULT + (fEff - 0.5) * 2.5),
            earlyBoostMinConfidence = max(0.40, 0.50 - (fEff - 0.5) * 0.20),
            earlyBoostMaxCommits = if (fEff >= 0.65) 4 else 3,
            lateCommitDecayFactor = run {
                val rawLcd = max(0.0, (fEff - 0.5) * 1.4 - vC * 0.6) + DFMapping.REF_LCD_DEFAULT
                val minLcd = if (fEff >= 0.65) 0.45 else 0.0
                rawLcd.coerceIn(minLcd, DFMapping.REF_LCD_MAX + 0.65)
            },
            lateCommitDecayThreshold = max(0.40, 0.55 - (fEff - 0.5) * 0.10),
            earlyRiseFracMin = min(0.65, 0.35 + (fEff - 0.5) * 0.50),
            peakMaxSlopeWeight = max(0.0, (fEff - 0.6) * 0.5),
            sustainedRiseSlopeMin = max(0.20, 0.40 - (fEff - 0.5) * 0.50),
            sustainedRiseMinTarget = max(5, (12 - ((fEff - 0.5) * 20).toInt() - (vC * 4).toInt()))
        )
    }

    @Test
    fun `percentages with three equal levels are exactly the old ones`() {
        for (d in dRaster) for (f in fRaster) for (v in vRaster) for (level in 1..9) {
            val expected = oldStv(d, f, v, level)
            val map = DFMapping.toStvMap(d, f, 85.0, v, agg = AggLevels.all(level))
            assertThat(Triple(map["sterkte"], map["timing"], map["volhoudendheid"])).isEqualTo(expected)
        }
    }

    @Test
    fun `old call with one level gives the same as three equal levels`() {
        for (d in dRaster) for (f in fRaster) for (level in 1..9) {
            assertThat(DFMapping.toStvMap(d, f, 85.0, 0.1, aggLevel = level))
                .isEqualTo(DFMapping.toStvMap(d, f, 85.0, 0.1, agg = AggLevels.all(level)))
            assertThat(DFMapping.toParamOverrides(d, f, vExtra = 0.1, aggLevel = level))
                .isEqualTo(DFMapping.toParamOverrides(d, f, vExtra = 0.1, agg = AggLevels.all(level)))
        }
    }

    @Test
    fun `all parameters with three equal levels are exactly the old ones`() {
        for (d in dRaster) for (f in fRaster) for (v in vRaster) for (level in 1..9) {
            val expected = oldParams(d, f, v, level)
            val actual = DFMapping.toParamOverrides(d, f, vExtra = v, agg = AggLevels.all(level))
            assertThat(actual).isEqualTo(expected)
        }
    }

    @Test
    fun `only the strength level changes strength and the d parameters`() {
        val base = DFMapping.toParamOverrides(1.05, 0.775, agg = AggLevels(5, 5, 5))
        val moved = DFMapping.toParamOverrides(1.05, 0.775, agg = AggLevels(8, 5, 5))
        val stvBase = DFMapping.toStvMap(1.05, 0.775, agg = AggLevels(5, 5, 5))
        val stvMoved = DFMapping.toStvMap(1.05, 0.775, agg = AggLevels(8, 5, 5))
        assertThat(stvMoved["timing"]).isEqualTo(stvBase["timing"])
        assertThat(stvMoved["sterkte"]).isNotEqualTo(stvBase["sterkte"])
        // Parameters that depend only on F do not move.
        assertThat(moved.commitCooldownMinutes).isEqualTo(base.commitCooldownMinutes)
        assertThat(moved.peakIobBrakeSuppressThreshold).isEqualTo(base.peakIobBrakeSuppressThreshold)
        assertThat(moved.lateCommitDecayFactor).isEqualTo(base.lateCommitDecayFactor)
        assertThat(moved.earlyBoostFactor).isEqualTo(base.earlyBoostFactor)
    }

    @Test
    fun `only the timing level changes timing and the f parameters`() {
        val base = DFMapping.toParamOverrides(1.05, 0.775, agg = AggLevels(5, 5, 5))
        val moved = DFMapping.toParamOverrides(1.05, 0.775, agg = AggLevels(5, 8, 5))
        val stvBase = DFMapping.toStvMap(1.05, 0.775, agg = AggLevels(5, 5, 5))
        val stvMoved = DFMapping.toStvMap(1.05, 0.775, agg = AggLevels(5, 8, 5))
        assertThat(stvMoved["sterkte"]).isEqualTo(stvBase["sterkte"])
        assertThat(stvMoved["volhoudendheid"]).isEqualTo(stvBase["volhoudendheid"])
        assertThat(stvMoved["timing"]).isNotEqualTo(stvBase["timing"])
        // The peak threshold depends only on D.
        assertThat(moved.peakPredictionThreshold).isEqualTo(base.peakPredictionThreshold)
    }

    @Test
    fun `only the persistence level changes the persistence percentage`() {
        val base = DFMapping.toParamOverrides(1.05, 0.775, agg = AggLevels(5, 5, 5))
        val moved = DFMapping.toParamOverrides(1.05, 0.775, agg = AggLevels(5, 5, 8))
        val stvBase = DFMapping.toStvMap(1.05, 0.775, agg = AggLevels(5, 5, 5))
        val stvMoved = DFMapping.toStvMap(1.05, 0.775, agg = AggLevels(5, 5, 8))
        assertThat(moved).isEqualTo(base)
        assertThat(stvMoved["sterkte"]).isEqualTo(stvBase["sterkte"])
        assertThat(stvMoved["timing"]).isEqualTo(stvBase["timing"])
        assertThat(stvMoved["volhoudendheid"]).isNotEqualTo(stvBase["volhoudendheid"])
    }

    @Test
    fun `raw percentages show a limit that is reached`() {
        // Persistence 120 at level 5. At level 9 the raw value is above 130 and the limit holds it at 130.
        val raw = DFMapping.toStvRaw(1.35, 0.775, 0.5, agg = AggLevels(5, 5, 9))
        assertThat(raw.third).isGreaterThan(DFMapping.V_MAX)
        assertThat(DFMapping.toStvMap(1.35, 0.775, 85.0, 0.5, agg = AggLevels(5, 5, 9))["volhoudendheid"]).isEqualTo(DFMapping.V_MAX)
    }
}
