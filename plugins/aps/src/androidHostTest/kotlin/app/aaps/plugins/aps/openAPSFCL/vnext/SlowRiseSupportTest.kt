package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SlowRiseSupportTest {

    private fun calc(
        strength: Double = 1.0, bg: Double = 9.0, target: Double = 5.4, slope: Double = 1.2, accel: Double = 0.0,
        iob: Double = 1.0, given: Double = 0.0, debt: Double = 0.0, proj: Double = 0.0, ratio: Double = 0.2,
        blocked: Boolean = false
    ) = SlowRiseSupport.calculate(strength, bg, target, slope, accel, iob, 3.0, given, debt, proj, ratio, blocked)

    @Test
    fun `strength 0 is off`() {
        assertThat(calc(strength = 0.0).doseU).isEqualTo(0.0)
    }

    @Test
    fun `a slow steady rise above target gives a small dose`() {
        val r = calc()
        assertThat(r.doseU).isGreaterThan(0.0)
        assertThat(r.doseU).isAtMost(SlowRiseSupport.CAP_U)
    }

    @Test
    fun `dose grows with strength and is capped by it`() {
        val low = calc(strength = 0.5).doseU
        val high = calc(strength = 1.5).doseU
        assertThat(high).isGreaterThan(low)
        assertThat(high).isAtMost(SlowRiseSupport.CAP_U * 1.5)
    }

    @Test
    fun `no dose for a flat or falling BG and none for a fast rise`() {
        assertThat(calc(slope = 0.0).doseU).isEqualTo(0.0)
        assertThat(calc(slope = -1.0).doseU).isEqualTo(0.0)
        assertThat(calc(slope = 3.5).doseU).isEqualTo(0.0)
    }

    @Test
    fun `no dose when blocked or at target`() {
        assertThat(calc(blocked = true).doseU).isEqualTo(0.0)
        assertThat(calc(bg = 5.4).doseU).isEqualTo(0.0)
    }

    @Test
    fun `lots of insulin given, high IOB or a hypo risk fade the dose out`() {
        val base = calc().doseU
        assertThat(calc(given = 4.0).doseU).isEqualTo(0.0)
        assertThat(calc(iob = 5.0).doseU).isEqualTo(0.0)
        assertThat(calc(debt = 0.6).doseU).isEqualTo(0.0)
        assertThat(calc(proj = 4.0).doseU).isEqualTo(0.0)
        assertThat(calc(ratio = 0.9).doseU).isEqualTo(0.0)
        assertThat(calc(given = 1.6).doseU).isLessThan(base)
    }

    @Test
    fun `the dose changes smoothly with the slope`() {
        var previous = calc(slope = 0.0).doseU
        var slope = 0.0
        while (slope <= 3.5) {
            val d = calc(slope = slope).doseU
            assertThat(Math.abs(d - previous)).isLessThan(0.05)
            previous = d
            slope += 0.05
        }
    }
}
