package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FastMealCapTest {

    @Test
    fun `strength 0 or no reference changes nothing`() {
        assertThat(FastMealCap.calculate(0.0, 1.2, 2.0, 4.0, 4.0, 4.0).limitedU).isEqualTo(2.0)
        assertThat(FastMealCap.calculate(1.0, Double.NaN, 2.0, 4.0, 4.0, 4.0).limitedU).isEqualTo(2.0)
    }

    @Test
    fun `a slow rise is not limited`() {
        assertThat(FastMealCap.calculate(1.0, 1.2, 2.0, 4.0, 9.0, 0.5).limitedU).isEqualTo(2.0)
        assertThat(FastMealCap.fastWeight(1.5)).isEqualTo(0.0)
        assertThat(FastMealCap.fastWeight(3.5)).isEqualTo(1.0)
    }

    @Test
    fun `a fast rise with the room used up is cut to nothing`() {
        // reference 1.2 U per mmol/L, rise 3, factor 1.1 at strength 1: allowed = 1.1 * 1.2 * 3 + 0.5 = 4.46 U
        val r = FastMealCap.calculate(1.0, 1.2, 1.0, 3.0, 6.0, 5.0)
        assertThat(r.limitedU).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a fast rise with room for the whole dose is not changed`() {
        val r = FastMealCap.calculate(1.0, 1.2, 0.5, 3.0, 1.0, 5.0)
        assertThat(r.limitedU).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `a higher strength gives a lower or equal result and never raises a dose`() {
        var previous = 2.0
        var strength = 0.0
        while (strength <= 1.5) {
            val d = FastMealCap.calculate(strength, 1.2, 2.0, 3.0, 3.0, 5.0).limitedU
            assertThat(d).isAtMost(previous + 1e-9)
            assertThat(d).isAtMost(2.0)
            previous = d
            strength += 0.1
        }
    }

    @Test
    fun `the result changes smoothly with the slope`() {
        var previous = FastMealCap.calculate(1.0, 1.2, 2.0, 3.0, 4.0, 0.0).limitedU
        var slope = 0.0
        while (slope <= 6.0) {
            val d = FastMealCap.calculate(1.0, 1.2, 2.0, 3.0, 4.0, slope).limitedU
            assertThat(Math.abs(d - previous)).isLessThan(0.1)
            previous = d
            slope += 0.05
        }
    }
}
