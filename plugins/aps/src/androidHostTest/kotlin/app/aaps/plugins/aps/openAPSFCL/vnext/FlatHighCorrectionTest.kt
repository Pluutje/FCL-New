package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FlatHighCorrectionTest {

    private fun calc(
        bg: Double = 7.4, target: Double = 5.4, slope: Double = 0.0, iob: Double = 0.5, isf: Double = 4.0,
        debt: Double = 0.0, proj: Double = 0.0, ratio: Double = 0.1, blocked: Boolean = false
    ) = FlatHighCorrection.calculate(bg, target, slope, iob, isf, debt, proj, ratio, blocked)

    @Test
    fun `flat BG 2 above target with low IOB gives a small dose`() {
        // 2.0 / 4.0 * 5 / 60 = 0.0417 U
        assertThat(calc().doseU).isWithin(1e-6).of(2.0 / 4.0 * 5.0 / 60.0)
    }

    @Test
    fun `no dose at or below target`() {
        assertThat(calc(bg = 5.4).doseU).isEqualTo(0.0)
        assertThat(calc(bg = 4.8).doseU).isEqualTo(0.0)
    }

    @Test
    fun `no dose when blocked`() {
        assertThat(calc(blocked = true).doseU).isEqualTo(0.0)
    }

    @Test
    fun `no dose for a clear rise or fall`() {
        assertThat(calc(slope = 1.5).doseU).isEqualTo(0.0)
        assertThat(calc(slope = -1.5).doseU).isEqualTo(0.0)
    }

    @Test
    fun `no dose with high IOB`() {
        assertThat(calc(iob = 3.0).doseU).isEqualTo(0.0)
    }

    @Test
    fun `no dose near a low projection or with hypo debt`() {
        assertThat(calc(proj = 4.4).doseU).isEqualTo(0.0)
        assertThat(calc(debt = 0.6).doseU).isEqualTo(0.0)
    }

    @Test
    fun `dose is never more than the cap`() {
        assertThat(calc(bg = 20.0, isf = 0.1).doseU).isAtMost(FlatHighCorrection.CAP_U)
    }

    @Test
    fun `dose grows smoothly with the excess`() {
        var previous = 0.0
        var bg = 5.4
        while (bg <= 9.0) {
            val d = calc(bg = bg).doseU
            assertThat(d).isAtLeast(previous - 1e-12)
            assertThat(d - previous).isLessThan(0.01)
            previous = d
            bg += 0.05
        }
    }

    @Test
    fun `dose falls smoothly with the IOB`() {
        var previous = calc(iob = 1.0).doseU
        var iob = 1.05
        while (iob <= 3.0) {
            val d = calc(iob = iob).doseU
            assertThat(d).isAtMost(previous + 1e-12)
            assertThat(previous - d).isLessThan(0.01)
            previous = d
            iob += 0.05
        }
    }

    @Test
    fun `nearest event inside the tolerance`() {
        val events = listOf(
            FlatHighCorrection.Event(1_000L, 0.05, 2.0, 0.9),
            FlatHighCorrection.Event(400_000L, 0.04, 1.8, 0.8)
        )
        assertThat(FlatHighCorrection.nearest(events, 10_000L, 150_000L)).isEqualTo(events[0])
        assertThat(FlatHighCorrection.nearest(events, 200_000L, 50_000L)).isNull()
    }
}
