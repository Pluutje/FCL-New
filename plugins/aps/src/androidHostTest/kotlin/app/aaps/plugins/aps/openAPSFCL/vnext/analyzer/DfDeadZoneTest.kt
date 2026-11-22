package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DfDeadZoneTest {

    @Test
    fun `weight is zero for a change of 1 point or less`() {
        assertThat(DfDeadZone.weight(0.0)).isEqualTo(0.0)
        assertThat(DfDeadZone.weight(1.0)).isEqualTo(0.0)
    }

    @Test
    fun `weight is one from 3 points`() {
        assertThat(DfDeadZone.weight(3.0)).isEqualTo(1.0)
        assertThat(DfDeadZone.weight(10.0)).isEqualTo(1.0)
    }

    @Test
    fun `weight rises smoothly in between`() {
        var last = -1.0
        var x = 1.0
        while (x <= 3.0) {
            val w = DfDeadZone.weight(x)
            assertThat(w).isAtLeast(last)
            last = w
            x += 0.1
        }
        assertThat(DfDeadZone.weight(2.0)).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `no last value gives the new value`() {
        val new = DfDeadZone.Df(1.1, 0.8, 0.0)
        assertThat(DfDeadZone.effective(null, new, AggLevels())).isEqualTo(new)
    }

    @Test
    fun `a step of 1 point is held back`() {
        val last = DfDeadZone.Df(1.000, 0.775, 0.0)
        // S = 95 * D, so a D step of 0.0105 moves S by 1 point.
        val new = DfDeadZone.Df(1.0105, 0.775, 0.0)
        val eff = DfDeadZone.effective(last, new, AggLevels())
        assertThat(eff).isEqualTo(last)
    }

    @Test
    fun `a big step goes through in full`() {
        val last = DfDeadZone.Df(1.0, 0.775, 0.0)
        val new = DfDeadZone.Df(1.2, 0.9, 0.0)
        val eff = DfDeadZone.effective(last, new, AggLevels())
        assertThat(eff.d).isWithin(1e-9).of(new.d)
        assertThat(eff.f).isWithin(1e-9).of(new.f)
    }

    @Test
    fun `percentages and parameters move by the same weight`() {
        val last = DfDeadZone.Df(1.0, 0.775, 0.0)
        val new = DfDeadZone.Df(1.0 + 0.021, 0.775 + 0.05, 0.1)
        val eff = DfDeadZone.effective(last, new, AggLevels())
        val w = DfDeadZone.weight(DfDeadZone.diffPoints(last, new, AggLevels()))
        assertThat(eff.d).isWithin(1e-9).of(last.d + (new.d - last.d) * w)
        assertThat(eff.f).isWithin(1e-9).of(last.f + (new.f - last.f) * w)
        assertThat(eff.vExtra).isWithin(1e-9).of(last.vExtra + (new.vExtra - last.vExtra) * w)
    }
}
