package app.aaps.plugins.aps.openAPSFCL.vnext

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OverrideBrakeScaleTest {

    @Test
    fun `no recent low gives severity 0`() {
        assertThat(OverrideBrakeScale.severity(5.0, 10.0)).isEqualTo(0.0)
        assertThat(OverrideBrakeScale.severity(4.4, 10.0)).isEqualTo(0.0)
    }

    @Test
    fun `deep and recent low gives severity 1`() {
        assertThat(OverrideBrakeScale.severity(3.6, 20.0)).isWithin(1e-9).of(1.0)
        assertThat(OverrideBrakeScale.severity(3.0, 30.0)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `an old low gives severity 0`() {
        assertThat(OverrideBrakeScale.severity(3.6, 60.0)).isWithin(1e-9).of(0.0)
        assertThat(OverrideBrakeScale.severity(3.6, 90.0)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `severity falls smoothly with age and rises smoothly with depth`() {
        var previous = 1.0
        var age = 30.0
        while (age <= 60.0) {
            val s = OverrideBrakeScale.severity(3.6, age)
            assertThat(s).isAtMost(previous + 1e-12)
            assertThat(previous - s).isLessThan(0.1)
            previous = s
            age += 0.5
        }
        previous = 0.0
        var lowest = 4.4
        while (lowest >= 3.6) {
            val s = OverrideBrakeScale.severity(lowest, 20.0)
            assertThat(s).isAtLeast(previous - 1e-12)
            assertThat(s - previous).isLessThan(0.1)
            previous = s
            lowest -= 0.01
        }
    }

    @Test
    fun `the 5 11 cases`() {
        // 17:56, 18:01, 18:06: lowest 3.6 at 17:21
        assertThat(OverrideBrakeScale.severity(3.6, 35.0)).isWithin(0.02).of(0.93)
        assertThat(OverrideBrakeScale.severity(3.6, 40.0)).isWithin(0.02).of(0.74)
        assertThat(OverrideBrakeScale.severity(3.6, 45.0)).isWithin(0.02).of(0.50)
    }

    @Test
    fun `without a positive override the brake is full`() {
        assertThat(OverrideBrakeScale.brakeStrength(false, 0.0)).isEqualTo(1.0)
        assertThat(OverrideBrakeScale.brakeStrength(false, 0.9)).isEqualTo(1.0)
        assertThat(OverrideBrakeScale.portionScale(false, 0.9)).isEqualTo(1.0)
    }

    @Test
    fun `with a positive override and no low the brake is off as before`() {
        assertThat(OverrideBrakeScale.brakeStrength(true, 0.0)).isEqualTo(0.0)
        assertThat(OverrideBrakeScale.portionScale(true, 0.0)).isEqualTo(1.0)
    }

    @Test
    fun `blend goes from no brake to full brake`() {
        assertThat(OverrideBrakeScale.blend(3.18, 0.3, 0.0)).isWithin(1e-9).of(3.18)
        assertThat(OverrideBrakeScale.blend(3.18, 0.3, 1.0)).isWithin(1e-9).of(0.3)
        assertThat(OverrideBrakeScale.blend(3.18, 0.3, 0.74)).isWithin(0.01).of(1.05)
    }

    @Test
    fun `portion scale is the rest of the strength`() {
        assertThat(OverrideBrakeScale.portionScale(true, 0.5)).isWithin(1e-9).of(0.5)
        assertThat(OverrideBrakeScale.portionScale(true, 1.0)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `nearest event inside the tolerance`() {
        val events = listOf(OverrideBrakeScale.Event(1_000L, 0.9, 1.5), OverrideBrakeScale.Event(400_000L, 0.5, 0.0))
        assertThat(OverrideBrakeScale.nearest(events, 10_000L, 150_000L)).isEqualTo(events[0])
        assertThat(OverrideBrakeScale.nearest(events, 200_000L, 50_000L)).isNull()
    }

    @Test
    fun `a BG that is back above the low range and rising releases the brake`() {
        // low 3.3, 20 minutes ago
        assertThat(OverrideBrakeScale.severity(3.3, 20.0, 3.9, 0.3)).isWithin(1e-9).of(1.0)   // still low
        assertThat(OverrideBrakeScale.severity(3.3, 20.0, 6.0, 0.0)).isWithin(1e-9).of(1.0)   // high enough but not rising
        assertThat(OverrideBrakeScale.severity(3.3, 20.0, 6.0, -0.3)).isWithin(1e-9).of(1.0)  // falling
        assertThat(OverrideBrakeScale.severity(3.3, 20.0, 5.6, 0.4)).isWithin(1e-9).of(0.0)   // back and rising
    }

    @Test
    fun `the release is smooth`() {
        val a = OverrideBrakeScale.severity(3.3, 20.0, 4.7, 0.3)
        val b = OverrideBrakeScale.severity(3.3, 20.0, 5.0, 0.3)
        val c = OverrideBrakeScale.severity(3.3, 20.0, 5.3, 0.3)
        assertThat(a).isGreaterThan(b)
        assertThat(b).isGreaterThan(c)
        assertThat(a).isLessThan(1.0)
    }
}
