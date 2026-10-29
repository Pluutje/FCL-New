package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.InsulinTimingHistory.Gate
import app.aaps.plugins.aps.openAPSFCL.vnext.database.CycleTimingRow
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class InsulinTimingHistoryTest {

    private val stepMs = 5 * 60_000L
    private val baseMs = 5_600_000L * stepMs

    private fun row(
        step: Int,
        bg: Double = 5.5,
        bolus: Double = 0.0,
        mealState: String = "NONE",
        doseAccess: String = "NORMAL",
        commitAllowed: Boolean = true,
        commitRaw: Double = 0.0,
        accel: Double = 0.0,
        slope: Double = 0.0,
        delta5m: Double = 0.0,
        target: Double = 5.4
    ) = CycleTimingRow(
        timestampMs = baseMs + step * stepMs,
        bg = bg,
        realDeliveredBolusU = bolus,
        realDeliveredBasalU = 0.0,
        profileBasalUH = 0.0,
        mealState = mealState,
        doseAccess = doseAccess,
        consistency = 0.9,
        effectiveCommitAllowed = commitAllowed,
        commitDoseRaw = commitRaw,
        accel = accel,
        recentSlope = slope,
        recentDelta5m = delta5m,
        target = target
    )

    /** Two flat days with one meal: the BG peaks 60 minutes after the start, 3 U is given 20 minutes after the start. */
    private fun mealRows(doseStep: Int): List<CycleTimingRow> = (0 until 576).map { step ->
        val k = step - 200
        val bg = if (k in 0..24) 5.5 + 3.5 * sin(PI * k / 24.0) else 5.5
        row(step, bg = bg, bolus = if (step == doseStep) 3.0 else 0.0)
    }

    @Test
    fun `insulin peak a little after the BG peak gives a small positive lag`() {
        val result = InsulinTimingHistory.analyze(mealRows(doseStep = 204))
        assertThat(result.episodes).hasSize(1)
        val e = result.episodes.first()
        assertThat(e.lagMin).isEqualTo(5)
        assertThat(e.insulinU).isWithin(1e-6).of(3.0)
        assertThat(e.rise).isGreaterThan(3.0)
    }

    @Test
    fun `earlier insulin gives a smaller lag`() {
        val late = InsulinTimingHistory.analyze(mealRows(doseStep = 208)).episodes.first().lagMin
        val early = InsulinTimingHistory.analyze(mealRows(doseStep = 202)).episodes.first().lagMin
        assertThat(early).isLessThan(late)
    }

    @Test
    fun `a flat day gives no meals`() {
        val rows = (0 until 576).map { row(it) }
        assertThat(InsulinTimingHistory.analyze(rows).episodes).isEmpty()
    }

    @Test
    fun `the first limit that is closed is reported in the order of the algorithm`() {
        assertThat(InsulinTimingHistory.gateOf(row(0, mealState = "NONE"))).isEqualTo(Gate.MEAL_NOT_RECOGNIZED)
        assertThat(InsulinTimingHistory.gateOf(row(0, mealState = "CONFIRMED", doseAccess = "SMALL")))
            .isEqualTo(Gate.ACCESS_LEVEL)
        assertThat(InsulinTimingHistory.gateOf(row(0, mealState = "CONFIRMED", commitAllowed = false)))
            .isEqualTo(Gate.COMMIT_PAUSE)
        assertThat(InsulinTimingHistory.gateOf(row(0, bg = 6.1, mealState = "CONFIRMED", slope = 2.0, delta5m = 0.3, accel = 0.2)))
            .isEqualTo(Gate.DELTA_TO_TARGET)
        assertThat(InsulinTimingHistory.gateOf(row(0, bg = 7.0, mealState = "CONFIRMED", slope = 0.2, delta5m = 0.02, accel = 0.2)))
            .isEqualTo(Gate.SLOW_RISE)
        assertThat(InsulinTimingHistory.gateOf(row(0, bg = 7.0, mealState = "CONFIRMED", slope = 2.0, delta5m = 0.3, accel = 0.05)))
            .isEqualTo(Gate.ACCELERATION)
        assertThat(InsulinTimingHistory.gateOf(row(0, bg = 7.0, mealState = "CONFIRMED", slope = 2.0, delta5m = 0.3, accel = 0.2)))
            .isEqualTo(Gate.OTHER)
    }

    @Test
    fun `waiting cycles before the first commit are counted with their limit`() {
        val rows = listOf(
            row(0),
            row(1, delta5m = 0.2, mealState = "NONE"),
            row(2, delta5m = 0.2, mealState = "NONE"),
            row(3, delta5m = 0.2, mealState = "CONFIRMED", doseAccess = "SMALL"),
            row(4, bg = 6.6, delta5m = 0.3, mealState = "CONFIRMED", commitRaw = 3.0)
        )
        val waits = InsulinTimingHistory.analyze(rows).waits
        assertThat(waits).hasSize(1)
        assertThat(waits.first().waitCycles).isEqualTo(3)
        assertThat(waits.first().waitMin).isEqualTo(15)
        assertThat(waits.first().gates)
            .containsExactly(Gate.MEAL_NOT_RECOGNIZED, Gate.MEAL_NOT_RECOGNIZED, Gate.ACCESS_LEVEL)
            .inOrder()
    }

    @Test
    fun `a second commit soon after the first is not a first commit`() {
        val rows = listOf(
            row(0, delta5m = 0.2, mealState = "CONFIRMED", commitRaw = 2.0),
            row(1, delta5m = 0.2, mealState = "CONFIRMED", commitRaw = 2.0)
        )
        assertThat(InsulinTimingHistory.analyze(rows).waits).hasSize(1)
    }

    @Test
    fun `the activity kernel adds up to one unit and peaks at the assumed time`() {
        val kernel = InsulinTimingHistory.activityKernel()
        assertThat(kernel.sum()).isWithin(0.01).of(1.0)
        val peakStep = kernel.indices.maxByOrNull { kernel[it] }!!
        assertThat(peakStep * InsulinTimingHistory.STEP_MIN).isEqualTo(InsulinTimingHistory.ASSUMED_PEAK_MIN.toInt())
    }

    @Test
    fun `median and percentile of a few values`() {
        assertThat(InsulinTimingHistory.median(listOf(5.0, 1.0, 3.0))).isEqualTo(3.0)
        assertThat(InsulinTimingHistory.median(listOf(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5)
        assertThat(InsulinTimingHistory.median(emptyList())).isNaN()
        assertThat(InsulinTimingHistory.percentile(listOf(0.0, 10.0), 0.5)).isEqualTo(5.0)
    }
}
