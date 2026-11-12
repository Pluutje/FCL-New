package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReboundBrakeTest {

    @Test
    fun `max cut is zero at strength zero and 50 percent at 75 percent`() {
        assertThat(ReboundBrake.maxCut(0.0)).isEqualTo(0.0)
        assertThat(ReboundBrake.maxCut(0.75)).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `max cut grows with the strength and never passes the limit`() {
        assertThat(ReboundBrake.maxCut(0.4)).isLessThan(ReboundBrake.maxCut(0.75))
        assertThat(ReboundBrake.maxCut(1.5)).isEqualTo(ReboundBrake.MAX_CUT)
    }

    @Test
    fun `severity is gradual with the depth of the low`() {
        assertThat(ReboundBrake.lowSeverity(5.2, 10.0)).isEqualTo(0.0)
        assertThat(ReboundBrake.lowSeverity(3.9, 10.0)).isWithin(1e-9).of(1.0)
        val mid = ReboundBrake.lowSeverity(4.5, 10.0)
        assertThat(mid).isGreaterThan(0.0)
        assertThat(mid).isLessThan(1.0)
        // 4.2 is a low too (the old brake needed 4.0)
        assertThat(ReboundBrake.lowSeverity(4.2, 10.0)).isGreaterThan(0.5)
    }

    @Test
    fun `severity fades out with the age of the low`() {
        val full = ReboundBrake.lowSeverity(4.0, 30.0)
        val later = ReboundBrake.lowSeverity(4.0, 70.0)
        val gone = ReboundBrake.lowSeverity(4.0, 95.0)
        assertThat(full).isWithin(1e-9).of(1.0)
        assertThat(later).isLessThan(full)
        assertThat(later).isGreaterThan(0.0)
        assertThat(gone).isEqualTo(0.0)
    }

    @Test
    fun `brake fades out when the BG is high`() {
        assertThat(ReboundBrake.bgFade(8.0)).isEqualTo(1.0)
        assertThat(ReboundBrake.bgFade(12.5)).isEqualTo(0.0)
        val mid = ReboundBrake.bgFade(10.5)
        assertThat(mid).isGreaterThan(0.0)
        assertThat(mid).isLessThan(1.0)
    }

    @Test
    fun `factor is one without a low or without strength`() {
        assertThat(ReboundBrake.factor(0.75, 5.5, 10.0, 6.0, 0.0)).isEqualTo(1.0)
        assertThat(ReboundBrake.factor(0.0, 3.8, 10.0, 6.0, 0.0)).isEqualTo(1.0)
    }

    @Test
    fun `factor holds back half the dose at reference strength after a deep recent low`() {
        assertThat(ReboundBrake.factor(0.75, 3.8, 20.0, 7.0, 0.0)).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `the cut of the post-hypo brake is not counted twice`() {
        val none = ReboundBrake.factor(0.75, 3.8, 20.0, 7.0, 0.0)
        val half = ReboundBrake.factor(0.75, 3.8, 20.0, 7.0, 0.5)
        val all = ReboundBrake.factor(0.75, 3.8, 20.0, 7.0, 1.0)
        assertThat(half).isGreaterThan(none)
        assertThat(all).isEqualTo(1.0)
    }

    @Test
    fun `factor never makes a dose bigger`() {
        for (s in listOf(0.0, 0.4, 0.75, 1.0, 1.5)) {
            for (low in listOf(3.0, 4.0, 4.5, 5.0, 6.0)) {
                val f = ReboundBrake.factor(s, low, 30.0, 8.0, 0.0)
                assertThat(f).isAtMost(1.0)
                assertThat(f).isAtLeast(1.0 - ReboundBrake.MAX_CUT - 1e-9)
            }
        }
    }

    @Test
    fun `without a positive override the factor does not change`() {
        assertThat(ReboundBrake.withOverride(0.5, 1.0)).isEqualTo(0.5)
    }

    @Test
    fun `with a positive override the brake is scaled down`() {
        assertThat(ReboundBrake.withOverride(0.5, 0.0)).isEqualTo(1.0)
        assertThat(ReboundBrake.withOverride(0.5, 0.5)).isWithin(1e-9).of(0.75)
        // a deep recent low keeps the brake, a low that has recovered does not
        val deepRecent = OverrideBrakeScale.severity(3.6, 10.0)
        val recovered = OverrideBrakeScale.severity(3.6, 10.0, bgNow = 6.0, recentDelta5m = 0.5)
        assertThat(ReboundBrake.withOverride(0.5, deepRecent)).isWithin(1e-9).of(0.5)
        assertThat(ReboundBrake.withOverride(0.5, recovered)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `lowest in window finds the lowest reading and its age`() {
        val now = 100L * 60_000L
        val history = listOf(
            (now - 120L * 60_000L) to 3.0,   // too old
            (now - 60L * 60_000L) to 4.3,
            (now - 40L * 60_000L) to 4.1,
            (now - 10L * 60_000L) to 6.0
        )
        val low = ReboundBrake.lowestInWindow(history, now)
        assertThat(low).isNotNull()
        assertThat(low!!.first).isEqualTo(4.1)
        assertThat(low.second).isWithin(1e-9).of(40.0)
        assertThat(ReboundBrake.lowestInWindow(emptyList(), now)).isNull()
    }
}
