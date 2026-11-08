package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EarlyBoostCapTest {

    @Test
    fun `cap weight is 1 for a low peak and 0 for a high peak`() {
        assertThat(EarlyBoostCap.capWeight(8.0)).isWithin(1e-9).of(1.0)
        assertThat(EarlyBoostCap.capWeight(8.5)).isWithin(1e-9).of(1.0)
        assertThat(EarlyBoostCap.capWeight(10.5)).isWithin(1e-9).of(0.0)
        assertThat(EarlyBoostCap.capWeight(14.0)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `cap weight falls smoothly with the predicted peak`() {
        var previous = 1.0
        var peak = 8.5
        while (peak <= 10.5) {
            val w = EarlyBoostCap.capWeight(peak)
            assertThat(w).isAtMost(previous + 1e-12)
            // no step: neighbours 0.05 mmol/L apart differ by a small amount
            assertThat(previous - w).isLessThan(0.1)
            previous = w
            peak += 0.05
        }
    }

    @Test
    fun `cap is three times what the peak needs`() {
        // 19:51 case: predicted peak 8.5, target 5.4, ISF 4.4
        assertThat(EarlyBoostCap.capU(8.5, 5.4, 4.4)).isWithin(0.01).of(2.11)
        assertThat(EarlyBoostCap.capU(5.0, 5.4, 4.4)).isEqualTo(0.0)
        // an ISF that is too low does not blow up the cap
        assertThat(EarlyBoostCap.capU(9.0, 5.4, 0.0)).isWithin(1e-9).of(3.0 * 3.6 / 0.5)
    }

    @Test
    fun `cover weight is 0 when the insulin on board does not cover the peak`() {
        // peak 12 is 6.6 above target, the cover is 3
        assertThat(EarlyBoostCap.coverWeight(12.0, 3.0, 5.4)).isEqualTo(0.0)
        // cover just equal to the need
        assertThat(EarlyBoostCap.coverWeight(9.0, 3.6, 5.4)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `cover weight is 1 when the cover is more than 2 above the need`() {
        // 19:56 case: peak 9.0, drop 9.2, target 5.4
        assertThat(EarlyBoostCap.coverWeight(9.0, 9.2, 5.4)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `cover weight grows smoothly`() {
        var previous = 0.0
        var drop = 3.6
        while (drop <= 5.6) {
            val w = EarlyBoostCap.coverWeight(9.0, drop, 5.4)
            assertThat(w).isAtLeast(previous - 1e-12)
            assertThat(w - previous).isLessThan(0.1)
            previous = w
            drop += 0.05
        }
    }

    @Test
    fun `faded boost factor goes from the factor to 1`() {
        assertThat(EarlyBoostCap.fadedBoostFactor(2.8, 0.0)).isWithin(1e-9).of(2.8)
        assertThat(EarlyBoostCap.fadedBoostFactor(2.8, 1.0)).isWithin(1e-9).of(1.0)
        assertThat(EarlyBoostCap.fadedBoostFactor(2.8, 0.5)).isWithin(1e-9).of(1.9)
        // a factor of 1 stays 1
        assertThat(EarlyBoostCap.fadedBoostFactor(1.0, 0.7)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `capped target is lowered only when the cap weight is on`() {
        // full weight: the cap
        assertThat(EarlyBoostCap.cappedTarget(5.47, 2.11, 1.0, 0.5)).isWithin(1e-9).of(2.11)
        // no weight: unchanged
        assertThat(EarlyBoostCap.cappedTarget(5.47, 2.11, 0.0, 0.5)).isWithin(1e-9).of(5.47)
        // half weight: in between
        assertThat(EarlyBoostCap.cappedTarget(5.47, 2.11, 0.5, 0.5)).isWithin(1e-9).of(3.79)
    }

    @Test
    fun `capped target is never raised by the cap`() {
        assertThat(EarlyBoostCap.cappedTarget(1.0, 2.11, 1.0, 0.5)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `capped target keeps the smallest early dose`() {
        assertThat(EarlyBoostCap.cappedTarget(5.47, 0.1, 1.0, 0.6)).isWithin(1e-9).of(0.6)
        // the floor never raises a target that is already lower
        assertThat(EarlyBoostCap.cappedTarget(0.4, 0.1, 1.0, 0.6)).isWithin(1e-9).of(0.4)
    }

    @Test
    fun `a real big meal is not limited`() {
        // predicted peak 13, drop 4: no cap and no cover fade
        assertThat(EarlyBoostCap.capWeight(13.0)).isEqualTo(0.0)
        assertThat(EarlyBoostCap.coverWeight(13.0, 4.0, 5.4)).isEqualTo(0.0)
    }

    @Test
    fun `commit of 8 November 23 12 is cut to about the cap`() {
        // predicted peak 6.7, target 5.4, ISF 3.5: cap = 3 * 1.3 / 3.5 = 1.11 U, the commit was 3.38 U
        val cap = EarlyBoostCap.capU(6.7, 5.4, 3.5)
        val cut = EarlyBoostCap.cappedTarget(3.38, cap, EarlyBoostCap.capWeight(6.7), 0.30)
        assertThat(cut).isWithin(0.01).of(1.11)
    }

    @Test
    fun `commit is not cut below the smallest commit`() {
        val cap = EarlyBoostCap.capU(5.6, 5.4, 3.5)
        val cut = EarlyBoostCap.cappedTarget(3.0, cap, EarlyBoostCap.capWeight(5.6), 0.30)
        assertThat(cut).isWithin(1e-9).of(0.30)
    }

    @Test
    fun `commit of a big meal with predicted peak 11 is not cut`() {
        val cap = EarlyBoostCap.capU(11.0, 5.4, 4.5)
        val cut = EarlyBoostCap.cappedTarget(3.5, cap, EarlyBoostCap.capWeight(11.0), 0.30)
        assertThat(cut).isEqualTo(3.5)
    }

    @Test
    fun `nearest finds the closest event inside the tolerance`() {
        val events = listOf(
            EarlyBoostCap.Event(1_000_000L, 5.47, 2.11, 0.3),
            EarlyBoostCap.Event(1_300_000L, 5.47, 2.45, 1.0)
        )
        assertThat(EarlyBoostCap.nearest(events, 1_010_000L, 150_000L)).isEqualTo(events[0])
        assertThat(EarlyBoostCap.nearest(events, 1_290_000L, 150_000L)).isEqualTo(events[1])
        assertThat(EarlyBoostCap.nearest(events, 1_150_000L, 100_000L)).isNull()
    }
}
