package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.InsulinTimingHistory
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class InsulinBudgetTest {

    @Test
    fun `allowed insulin grows with the rise and starts at the margin`() {
        assertThat(InsulinBudget.allowedU(0.8, 1.5, 0.0, 0.5)).isWithin(1e-9).of(0.5)
        assertThat(InsulinBudget.allowedU(0.8, 1.5, 3.0, 0.5)).isWithin(1e-9).of(4.1)
        // A falling BG (negative rise) does not make the room smaller than the margin.
        assertThat(InsulinBudget.allowedU(0.8, 1.5, -2.0, 0.5)).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `room factor is 1 with room 0 without and smooth in between`() {
        assertThat(InsulinBudget.roomFactor(2.0, 1.0)).isEqualTo(1.0)
        assertThat(InsulinBudget.roomFactor(1.0, 1.0)).isEqualTo(1.0)
        assertThat(InsulinBudget.roomFactor(0.0, 1.0)).isEqualTo(0.0)
        assertThat(InsulinBudget.roomFactor(-1.0, 1.0)).isEqualTo(0.0)
        assertThat(InsulinBudget.roomFactor(0.5, 1.0)).isWithin(1e-9).of(0.5)
        assertThat(InsulinBudget.roomFactor(0.5, 0.0)).isEqualTo(1.0)
    }

    @Test
    fun `room factor never goes down when there is more room`() {
        var previous = 0.0
        var room = -0.5
        while (room <= 2.0) {
            val f = InsulinBudget.roomFactor(room, 1.0)
            assertThat(f).isAtLeast(previous)
            previous = f
            room += 0.01
        }
    }

    @Test
    fun `rising weight is 0 for a flat BG and 1 for a clear rise`() {
        assertThat(InsulinBudget.risingWeight(-2.0)).isEqualTo(0.0)
        assertThat(InsulinBudget.risingWeight(InsulinBudget.RISE_SLOPE_START)).isEqualTo(0.0)
        assertThat(InsulinBudget.risingWeight(1.0)).isWithin(1e-9).of(0.5)
        assertThat(InsulinBudget.risingWeight(InsulinBudget.RISE_SLOPE_FULL)).isEqualTo(1.0)
        assertThat(InsulinBudget.risingWeight(6.0)).isEqualTo(1.0)
    }

    @Test
    fun `limited dose follows the factor and the weight and never raises a dose`() {
        assertThat(InsulinBudget.limitedDose(2.0, 1.0, 1.0)).isEqualTo(2.0)
        assertThat(InsulinBudget.limitedDose(2.0, 0.0, 0.0)).isEqualTo(2.0)
        assertThat(InsulinBudget.limitedDose(2.0, 0.0, 1.0)).isEqualTo(0.0)
        assertThat(InsulinBudget.limitedDose(2.0, 0.5, 0.5)).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `the rise starts at the last lowest BG of the last 90 minutes`() {
        val now = 10_000_000_000L
        val min = 60_000L
        val history = listOf(
            (now - 120 * min) to 3.0,   // too old
            (now - 80 * min) to 5.0,
            (now - 60 * min) to 4.5,
            (now - 40 * min) to 4.5,    // same value, later: this is the start
            (now - 20 * min) to 6.0
        )
        val base = InsulinBudget.riseBase(history, now, 7.0)!!
        assertThat(base.first).isEqualTo(4.5)
        assertThat(base.second).isEqualTo(now - 40 * min)
    }

    @Test
    fun `when the BG is the lowest now the rise has not started`() {
        val now = 10_000_000_000L
        val base = InsulinBudget.riseBase(listOf((now - 30 * 60_000L) to 6.0), now, 5.5)!!
        assertThat(base.first).isEqualTo(5.5)
        assertThat(base.second).isEqualTo(now)
    }

    private fun episode(rise: Double, insulin: Double) =
        InsulinTimingHistory.Episode(peakMs = 0L, bgPeak = 10.0, rise = rise, riseMin = 60, insulinU = insulin, lagMin = 20)

    @Test
    fun `reference needs enough meals`() {
        val few = List(InsulinBudget.MIN_EPISODES - 1) { episode(4.0, 4.0) }
        assertThat(InsulinBudget.referenceFromEpisodes(few).isNaN()).isTrue()
        val enough = List(InsulinBudget.MIN_EPISODES) { episode(4.0, 4.0) }
        assertThat(InsulinBudget.referenceFromEpisodes(enough)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `reference is the median and skips small rises`() {
        val meals = List(10) { episode(4.0, 2.0) } + List(5) { episode(4.0, 6.0) } + List(5) { episode(1.0, 5.0) }
        // The 5 small rises are skipped: 10 x 0.5 and 5 x 1.5 are 15 meals, the median is 0.5.
        assertThat(InsulinBudget.referenceFromEpisodes(meals)).isWithin(1e-9).of(0.5)
    }
}
