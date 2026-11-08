package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CoverIsfTest {

    @Test
    fun `factor is one at and below target`() {
        assertThat(CoverIsf.factorAbove(0.0)).isEqualTo(1.0)
        assertThat(CoverIsf.factorAbove(-1.0)).isEqualTo(1.0)
    }

    @Test
    fun `factor is unchanged up to 7 above target`() {
        assertThat(CoverIsf.factorAbove(3.0)).isWithin(1e-9).of(0.67)
        assertThat(CoverIsf.factorAbove(7.0)).isWithin(1e-9).of(0.50)
    }

    @Test
    fun `factor keeps falling above 7 and stays above the last point`() {
        assertThat(CoverIsf.factorAbove(10.0)).isWithin(1e-9).of(0.42)
        assertThat(CoverIsf.factorAbove(13.0)).isWithin(1e-9).of(0.36)
        assertThat(CoverIsf.factorAbove(30.0)).isWithin(1e-9).of(0.36)
    }

    @Test
    fun `factor never rises when the BG gets higher`() {
        var previous = 1.0
        var x = 0.0
        while (x <= 20.0) {
            val f = CoverIsf.factorAbove(x)
            assertThat(f).isAtMost(previous + 1e-12)
            assertThat(previous - f).isLessThan(0.05)
            previous = f
            x += 0.1
        }
    }
}
