package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DowntrendPassTest {

    private val s75 = 0.75

    @Test
    fun `strength 0 gives no pass`() {
        assertThat(DowntrendPass.weight(4.0, -0.3, 0.05, 0.0)).isEqualTo(0.0)
        assertThat(DowntrendPass.maxPass(0.0)).isEqualTo(0.0)
    }

    @Test
    fun `at 75 percent the maximum pass is 70 percent`() {
        assertThat(DowntrendPass.maxPass(0.75)).isWithin(1e-9).of(0.7)
        assertThat(DowntrendPass.weight(4.0, -0.3, 0.05, s75)).isWithin(1e-9).of(0.7)
    }

    @Test
    fun `the maximum grows with the strength`() {
        assertThat(DowntrendPass.maxPass(0.3)).isLessThan(DowntrendPass.maxPass(0.5))
        assertThat(DowntrendPass.maxPass(0.5)).isLessThan(DowntrendPass.maxPass(0.75))
        assertThat(DowntrendPass.maxPass(0.75)).isLessThan(DowntrendPass.maxPass(1.0))
    }

    @Test
    fun `the gate never opens completely`() {
        assertThat(DowntrendPass.maxPass(1.5)).isEqualTo(DowntrendPass.ABS_MAX)
        assertThat(DowntrendPass.weight(8.0, 0.0, 0.0, 1.5)).isAtMost(DowntrendPass.ABS_MAX)
    }

    @Test
    fun `close to target gives no pass`() {
        assertThat(DowntrendPass.weight(1.0, -0.8, 0.05, 1.0)).isEqualTo(0.0)
    }

    @Test
    fun `a steep fall closes the pass`() {
        assertThat(DowntrendPass.weight(4.0, -2.2, 0.05, 1.0)).isEqualTo(0.0)
    }

    @Test
    fun `a high IOB ratio closes the pass`() {
        assertThat(DowntrendPass.weight(4.0, -0.3, 0.50, 1.0)).isEqualTo(0.0)
    }

    @Test
    fun `pass grows gradually with the height above target`() {
        val low = DowntrendPass.weight(2.0, -0.8, 0.10, s75)
        val mid = DowntrendPass.weight(2.8, -0.8, 0.10, s75)
        val high = DowntrendPass.weight(3.6, -0.8, 0.10, s75)
        assertThat(low).isGreaterThan(0.0)
        assertThat(low).isLessThan(mid)
        assertThat(mid).isLessThan(high)
    }

    @Test
    fun `pass falls gradually with the fall speed`() {
        val mild = DowntrendPass.weight(3.5, -0.8, 0.10, s75)
        val steeper = DowntrendPass.weight(3.5, -1.3, 0.10, s75)
        assertThat(steeper).isLessThan(mild)
        assertThat(steeper).isGreaterThan(0.0)
    }
}
