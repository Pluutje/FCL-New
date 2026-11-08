package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AbsorptionBlockWeightTest {

    private fun w(
        window: Boolean = true, accel: Double = -0.3, iob: Double = 0.6, cons: Double = 0.9,
        slope: Double = 0.0, delta: Double = 1.0
    ) = AbsorptionBlockWeight.calculate(window, accel, iob, cons, slope, delta)

    @Test
    fun `outside the window there is no block`() {
        assertThat(w(window = false)).isEqualTo(0.0)
    }

    @Test
    fun `clear turn with enough IOB near target is a full block`() {
        assertThat(w()).isEqualTo(1.0)
    }

    @Test
    fun `positive acceleration gives no block`() {
        assertThat(w(accel = 0.1)).isEqualTo(0.0)
    }

    @Test
    fun `weight grows smoothly with the deceleration`() {
        val a = w(accel = -0.07)
        val b = w(accel = -0.10)
        val c = w(accel = -0.13)
        assertThat(a).isLessThan(b)
        assertThat(b).isLessThan(c)
        assertThat(a).isGreaterThan(0.0)
    }

    @Test
    fun `steep rise far above target releases the block (20 27 case)`() {
        assertThat(w(accel = -0.13, iob = 0.39, cons = 0.9, slope = 5.1, delta = 10.8)).isEqualTo(0.0)
    }

    @Test
    fun `release is gradual`() {
        val low = w(slope = 2.5, delta = 4.0)
        val high = w(slope = 3.5, delta = 5.5)
        assertThat(low).isGreaterThan(high)
        assertThat(high).isGreaterThan(0.0)
    }
}
