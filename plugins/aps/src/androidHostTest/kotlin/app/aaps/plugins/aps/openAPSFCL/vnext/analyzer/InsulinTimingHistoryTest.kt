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

    /** A row that opens every limit unless the test closes one: a recognized meal, a fast and accelerating rise, 1.6 mmol/L above target. */
    private fun openRow(
        step: Int = 0,
        bg: Double = 7.0,
        mealState: String = "CONFIRMED",
        doseAccess: String = "NORMAL",
        commitAllowed: Boolean = true,
        accel: Double = 0.2,
        slope: Double = 2.0,
        delta5m: Double = 0.3
    ) = row(step, bg = bg, mealState = mealState, doseAccess = doseAccess, commitAllowed = commitAllowed,
            accel = accel, slope = slope, delta5m = delta5m)

    @Test
    fun `an open row has no closed limit`() {
        assertThat(InsulinTimingHistory.closedGates(openRow())).isEmpty()
    }

    @Test
    fun `each limit is reported on its own`() {
        assertThat(InsulinTimingHistory.closedGates(openRow(mealState = "NONE")))
            .containsExactly(Gate.MEAL_NOT_RECOGNIZED)
        assertThat(InsulinTimingHistory.closedGates(openRow(doseAccess = "SMALL")))
            .containsExactly(Gate.ACCESS_LEVEL)
        assertThat(InsulinTimingHistory.closedGates(openRow(commitAllowed = false)))
            .containsExactly(Gate.COMMIT_PAUSE)
        assertThat(InsulinTimingHistory.closedGates(openRow(bg = 5.8)))
            .containsExactly(Gate.DELTA_TO_TARGET)
        assertThat(InsulinTimingHistory.closedGates(openRow(slope = 0.2, delta5m = 0.02)))
            .containsExactly(Gate.SLOW_RISE)
        assertThat(InsulinTimingHistory.closedGates(openRow(accel = 0.05)))
            .containsExactly(Gate.ACCELERATION)
    }

    @Test
    fun `several closed limits are all reported`() {
        val closed = InsulinTimingHistory.closedGates(openRow(mealState = "NONE", bg = 5.5, slope = 0.1, delta5m = 0.01, accel = 0.0))
        assertThat(closed).containsExactly(
            Gate.MEAL_NOT_RECOGNIZED, Gate.DELTA_TO_TARGET, Gate.SLOW_RISE, Gate.ACCELERATION
        )
    }

    @Test
    fun `the commit pause is not reported when the meal is not recognized`() {
        // The algorithm only writes the commit pause when it runs the commit path, which needs a recognized meal.
        assertThat(InsulinTimingHistory.closedGates(openRow(mealState = "NONE", commitAllowed = false)))
            .containsExactly(Gate.MEAL_NOT_RECOGNIZED)
    }

    @Test
    fun `the smooth start of the commit is open and is marked`() {
        // Target 5.4: 0.7 mmol/L above target is inside the smooth start, 0.4 is below it, 0.9 is above it.
        val inRamp = InsulinTimingHistory.waitCycleOf(openRow(bg = 6.1))
        assertThat(inRamp.closed).isEmpty()
        assertThat(inRamp.inRamp).isTrue()
        val below = InsulinTimingHistory.waitCycleOf(openRow(bg = 5.8))
        assertThat(below.closed).containsExactly(Gate.DELTA_TO_TARGET)
        assertThat(below.inRamp).isFalse()
        val above = InsulinTimingHistory.waitCycleOf(openRow(bg = 6.3))
        assertThat(above.closed).isEmpty()
        assertThat(above.inRamp).isFalse()
    }

    @Test
    fun `waiting cycles before the first commit are counted with their limits`() {
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
        // The first two cycles had no meal. In the third the meal was recognized but the access level was small.
        assertThat(waits.first().cycles.map { it.closed.first() })
            .containsExactly(Gate.MEAL_NOT_RECOGNIZED, Gate.MEAL_NOT_RECOGNIZED, Gate.ACCESS_LEVEL)
            .inOrder()
        assertThat(waits.first().cycles[2].closed).doesNotContain(Gate.MEAL_NOT_RECOGNIZED)
    }

    @Test
    fun `the summary counts a limit only when it was the only closed one`() {
        val day = 24 * 60 * 60_000L
        val wait = InsulinTimingHistory.FirstCommitWait(
            timeMs = baseMs + day,
            waitCycles = 5,
            waitMin = 25,
            cycles = listOf(
                InsulinTimingHistory.WaitCycle(setOf(Gate.DELTA_TO_TARGET), inRamp = false),
                InsulinTimingHistory.WaitCycle(setOf(Gate.DELTA_TO_TARGET), inRamp = false),
                InsulinTimingHistory.WaitCycle(setOf(Gate.MEAL_NOT_RECOGNIZED, Gate.DELTA_TO_TARGET, Gate.SLOW_RISE), inRamp = false),
                InsulinTimingHistory.WaitCycle(emptySet(), inRamp = true),
                InsulinTimingHistory.WaitCycle(setOf(Gate.ACCELERATION), inRamp = false)
            )
        )
        val result = InsulinTimingHistory.Result(emptyList(), listOf(wait))
        val zone = java.time.ZoneOffset.UTC
        val today = java.time.Instant.ofEpochMilli(baseMs + day).atZone(zone).toLocalDate()
        val s = InsulinTimingHistory.summary(result, today, 28, zone)
        assertThat(s.waitCycles).isEqualTo(5)
        assertThat(s.soleGateCounts[Gate.DELTA_TO_TARGET]).isEqualTo(2)
        assertThat(s.soleGateCounts[Gate.ACCELERATION]).isEqualTo(1)
        assertThat(s.soleGateCounts[Gate.MEAL_NOT_RECOGNIZED]).isNull()
        assertThat(s.severalGatesCycles).isEqualTo(1)
        assertThat(s.openCycles).isEqualTo(1)
        assertThat(s.rampCycles).isEqualTo(1)
        assertThat(InsulinTimingHistory.topGate(s)).isEqualTo(Gate.DELTA_TO_TARGET to 40)
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
