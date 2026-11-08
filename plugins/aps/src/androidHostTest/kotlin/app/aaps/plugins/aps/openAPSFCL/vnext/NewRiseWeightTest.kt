package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NewRiseWeightTest {

    @Test
    fun `no history gives zero`() {
        assertThat(NewRiseWeight.calculate(8.0, emptyList(), 3.0)).isEqualTo(0.0)
    }

    @Test
    fun `flat or falling BG gives zero`() {
        assertThat(NewRiseWeight.calculate(8.0, listOf(8.0, 8.1, 8.0), 0.0)).isEqualTo(0.0)
        assertThat(NewRiseWeight.calculate(7.0, listOf(9.0, 8.0, 7.0), -2.0)).isEqualTo(0.0)
    }

    @Test
    fun `clear rise from the low point gives full weight`() {
        assertThat(NewRiseWeight.calculate(9.0, listOf(7.5, 8.0, 8.5, 9.0), 3.0)).isEqualTo(1.0)
    }

    @Test
    fun `weight grows smoothly with the rise`() {
        val small = NewRiseWeight.calculate(7.2, listOf(7.0, 7.2), 3.0)
        val medium = NewRiseWeight.calculate(7.4, listOf(7.0, 7.4), 3.0)
        val big = NewRiseWeight.calculate(7.8, listOf(7.0, 7.8), 3.0)
        assertThat(small).isLessThan(medium)
        assertThat(medium).isLessThan(big)
        assertThat(small).isGreaterThan(0.0)
    }

    @Test
    fun `a big rise with a low slope gives a low weight`() {
        assertThat(NewRiseWeight.calculate(9.0, listOf(7.0, 9.0), 0.5)).isEqualTo(0.0)
    }
}
